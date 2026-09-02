package com.teng.app.gastosai.service;

import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.ProjectResponse;
import com.teng.app.gastosai.entity.User;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Renders the expense list as a PDF a freelancer can attach to an invoice.
 *
 * <p>The rows come from {@link ExpenseService#findAll(User, LocalDate, LocalDate,
 * com.teng.app.gastosai.entity.ExpenseSource, Long)} — the same call the Expenses screen reads, at
 * the same rounding — so a total on the page reconciles with the app to the centavo instead of
 * being computed a second way here. Nothing in this class touches an amount beyond summing what
 * that call already scaled.
 *
 * <p>Rendering is a package-private static so it can be unit-tested without a Spring context, per
 * the domain/service testing rule.
 */
@Service
@RequiredArgsConstructor
public class ExpensePdfExportService {

	private static final ZoneId MANILA = ZoneId.of("Asia/Manila");

	private static final PDFont BODY = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
	private static final PDFont BOLD = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

	private static final float MARGIN = 40f;
	private static final float TITLE_SIZE = 16f;
	private static final float META_SIZE = 9f;
	private static final float BODY_SIZE = 9f;
	private static final float ROW_HEIGHT = 15f;

	/**
	 * Date · Description · Category · Amount (as entered) · Amount (PHP). Sums to 515pt of A4.
	 *
	 * <p>The two money columns are the widest they can be without starving the description, because
	 * an amount is never clipped: what does not fit is shrunk instead (see {@link #fittingSize}), and
	 * a wider column is what keeps the common case at full body size.
	 */
	private static final float[] COLUMN_WIDTHS = {70f, 165f, 80f, 95f, 105f};
	private static final String[] HEADERS = {"Date", "Description", "Category", "Amount", "Amount (PHP)"};

	private static final DateTimeFormatter ROW_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter GENERATED_AT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'GMT'xxx");

	private final ExpenseService expenseService;

	/**
	 * The report for a date range, optionally narrowed to one project or client tag.
	 *
	 * @param from inclusive lower bound, or null for no lower bound
	 * @param to inclusive upper bound, or null for no upper bound
	 * @param projectId the tag to bill, or null for every expense in the range
	 */
	@Transactional(readOnly = true)
	public byte[] exportPdf(User user, LocalDate from, LocalDate to, Long projectId) throws IOException {
		List<ExpenseResponse> expenses = expenseService.findAll(user, from, to, null, projectId);
		// Resolved from the user's tags rather than from a row, so an empty range still names the
		// project it found nothing for — a zero-total report is a valid answer to send a client.
		String projectName = projectId == null ? null : expenseService.projects(user).stream()
				.filter(p -> projectId.equals(p.id()))
				.map(ProjectResponse::name)
				.findFirst()
				.orElse(null);
		return render(expenses, from, to, projectName);
	}

	static byte[] render(List<ExpenseResponse> expenses, LocalDate from, LocalDate to, String projectName)
			throws IOException {
		// Chronological, because the document is read as a statement. The API returns newest-first
		// for the screen; re-sorting changes nothing about which rows are in it or what they sum to.
		List<ExpenseResponse> rows = expenses.stream()
				.sorted(Comparator.comparing(ExpenseResponse::date))
				.toList();

		try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			// The cursor is a resource of its own: a row that throws mid-page must still close the
			// page's content stream, before the document it belongs to is closed or saved.
			try (Cursor cursor = new Cursor(document)) {
				cursor.startPage();
				writeTitleBlock(cursor, from, to, projectName, rows.size());
				writeTableHeader(cursor);

				BigDecimal total = BigDecimal.ZERO;
				for (ExpenseResponse e : rows) {
					if (cursor.y < MARGIN + ROW_HEIGHT * 3) {
						cursor.endPage();
						cursor.startPage();
						writeTableHeader(cursor);
					}
					writeRow(cursor, BODY, new String[]{
							e.date().format(ROW_DATE),
							sanitize(e.description()),
							sanitize(e.category()),
							sanitize(e.currency()) + " " + money(e.amount()),
							money(e.amountInBaseCurrency())
					});
					total = total.add(e.amountInBaseCurrency());
				}

				cursor.y -= 4f;
				cursor.rule();
				writeRow(cursor, BOLD,
						new String[]{"", "", "", "Total (PHP)", money(total.setScale(2, RoundingMode.HALF_UP))});
				cursor.endPage();
			}

			document.save(out);
			return out.toByteArray();
		}
	}

	private static void writeTitleBlock(Cursor cursor, LocalDate from, LocalDate to, String projectName, int count)
			throws IOException {
		cursor.text(BOLD, TITLE_SIZE, MARGIN, "Expense Report");
		cursor.y -= TITLE_SIZE + 6f;
		cursor.text(BODY, META_SIZE, MARGIN, "Period: " + describePeriod(from, to));
		cursor.y -= META_SIZE + 3f;
		cursor.text(BODY, META_SIZE, MARGIN,
				"Project / client: " + (projectName == null ? "All" : sanitize(projectName)));
		cursor.y -= META_SIZE + 3f;
		cursor.text(BODY, META_SIZE, MARGIN,
				count + (count == 1 ? " expense" : " expenses")
						+ "  ·  Generated " + ZonedDateTime.now(MANILA).format(GENERATED_AT));
		cursor.y -= META_SIZE + 10f;
	}

	private static void writeTableHeader(Cursor cursor) throws IOException {
		writeRow(cursor, BOLD, HEADERS);
		cursor.rule();
	}

	private static void writeRow(Cursor cursor, PDFont font, String[] cells) throws IOException {
		float x = MARGIN;
		for (int i = 0; i < cells.length; i++) {
			float width = COLUMN_WIDTHS[i] - 6f;
			// The two money columns are right-aligned so the digits line up down the page; a
			// truncated amount would be a wrong number, so only text columns are ever clipped.
			// An amount too wide for its column is drawn smaller instead, which keeps every digit
			// and keeps it inside the column rather than colliding with the one beside it.
			boolean numeric = i >= 3;
			String text = numeric ? cells[i] : clip(font, cells[i], width);
			float size = numeric ? fittingSize(font, text, width) : BODY_SIZE;
			float offset = numeric ? width - textWidth(font, text, size) : 0f;
			cursor.text(font, size, x + Math.max(offset, 0f), text);
			x += COLUMN_WIDTHS[i];
		}
		cursor.y -= ROW_HEIGHT;
	}

	private static String describePeriod(LocalDate from, LocalDate to) {
		if (from == null && to == null) {
			return "All time";
		}
		if (from == null) {
			return "Up to " + to;
		}
		if (to == null) {
			return "From " + from;
		}
		return from + " to " + to;
	}

	private static String money(BigDecimal amount) {
		// Plain grouping, no currency symbol: the peso sign is outside WinAnsiEncoding, which is
		// what the Standard 14 fonts above encode with. The column header carries the currency.
		DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
		return format.format(amount == null ? BigDecimal.ZERO : amount);
	}

	/**
	 * Reduce a value to what WinAnsiEncoding can actually draw. A description carrying an emoji or a
	 * CJK character would otherwise abort the whole export with an encoding error at render time,
	 * turning one odd expense into a feature that does not work.
	 */
	private static String sanitize(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		StringBuilder safe = new StringBuilder(value.length());
		value.codePoints().forEach(cp -> {
			if (cp >= 0x20 && cp <= 0x7E) {
				safe.append((char) cp);
			} else if (cp >= 0xA0 && cp <= 0xFF) {
				safe.append((char) cp);
			} else {
				safe.append('?');
			}
		});
		return safe.toString();
	}

	/**
	 * The largest font size, never above {@link #BODY_SIZE}, at which {@code text} fits in
	 * {@code maxWidth}. There is no lower bound on purpose: a floor would put the overflow back, and
	 * an unreadably small amount is still a readable-with-a-zoom correct number, where a clipped one
	 * is a wrong number. The 6pt of column padding absorbs the rounding on an exact fit.
	 */
	private static float fittingSize(PDFont font, String text, float maxWidth) throws IOException {
		float natural = textWidth(font, text, BODY_SIZE);
		if (natural <= maxWidth || natural <= 0f) {
			return BODY_SIZE;
		}
		return BODY_SIZE * maxWidth / natural;
	}

	private static String clip(PDFont font, String text, float maxWidth) throws IOException {
		if (textWidth(font, text, BODY_SIZE) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		StringBuilder kept = new StringBuilder();
		for (char c : text.toCharArray()) {
			if (textWidth(font, kept.toString() + c + ellipsis, BODY_SIZE) > maxWidth) {
				break;
			}
			kept.append(c);
		}
		return kept + ellipsis;
	}

	private static float textWidth(PDFont font, String text, float size) throws IOException {
		return font.getStringWidth(text) / 1000f * size;
	}

	/** A page under construction: the open content stream and the baseline the next line sits on. */
	private static final class Cursor implements Closeable {

		private final PDDocument document;
		private PDPageContentStream stream;
		private float y;

		private Cursor(PDDocument document) {
			this.document = document;
		}

		private void startPage() throws IOException {
			PDPage page = new PDPage(PDRectangle.A4);
			document.addPage(page);
			stream = new PDPageContentStream(document, page);
			y = PDRectangle.A4.getHeight() - MARGIN;
		}

		private void endPage() throws IOException {
			stream.close();
			stream = null;
		}

		/** No-op once {@link #endPage()} has run; the safety net for a page abandoned mid-write. */
		@Override
		public void close() throws IOException {
			if (stream != null) {
				stream.close();
				stream = null;
			}
		}

		private void text(PDFont font, float size, float x, String value) throws IOException {
			stream.beginText();
			stream.setFont(font, size);
			stream.newLineAtOffset(x, y);
			stream.showText(value);
			stream.endText();
		}

		/** A horizontal line in the gap between the row just written and the one about to be. */
		private void rule() throws IOException {
			float lineY = y + 5f;
			stream.moveTo(MARGIN, lineY);
			stream.lineTo(PDRectangle.A4.getWidth() - MARGIN, lineY);
			stream.stroke();
		}
	}
}
