package com.teng.app.gastosai;

import com.teng.app.gastosai.dto.ExpenseResponse;
import com.teng.app.gastosai.dto.ProjectResponse;
import com.teng.app.gastosai.entity.ExpenseSource;
import com.teng.app.gastosai.entity.User;
import com.teng.app.gastosai.service.ExpensePdfExportService;
import com.teng.app.gastosai.service.ExpenseService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PDF export, asserted by reading the produced document back rather than by trusting the
 * renderer. No Spring context: {@link ExpensePdfExportService} depends only on
 * {@link ExpenseService}, which is stubbed, so this stays a service-layer unit test.
 *
 * <p>The acceptance criterion that actually needs guarding is "amounts match the app to the
 * centavo": the export must read the same rows the Expenses screen reads and must not round them a
 * second time. Both halves are asserted below — the delegation, and the digits on the page.
 */
class ExpensePdfExportServiceTest {

	private final ExpenseService expenseService = mock(ExpenseService.class);
	private final ExpensePdfExportService service = new ExpensePdfExportService(expenseService);
	private final User user = new User();

	@Test
	void rendersEveryExpenseAndATotalThatMatchesToTheCentavo() throws IOException {
		when(expenseService.findAll(any(), any(), any(), any(), any())).thenReturn(List.of(
				expense(1L, "12.34", "Grab to client site", "Transport"),
				expense(2L, "1000.05", "Coworking day pass", "Office"),
				expense(3L, "0.01", "Rounding edge", "Misc")));

		String text = textOf(service.exportPdf(user, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), null));

		assertThat(text).contains("Expense Report");
		assertThat(text).contains("2026-08-01 to 2026-08-31");
		assertThat(text).contains("Grab to client site", "Coworking day pass", "Rounding edge");
		assertThat(text).contains("12.34", "1,000.05", "0.01");
		// 12.34 + 1000.05 + 0.01, summed from the same scaled values the API serves.
		assertThat(text).contains("Total (PHP)").contains("1,012.40");
	}

	@Test
	void readsTheSameRowsTheExpensesScreenReads() throws IOException {
		when(expenseService.findAll(any(), any(), any(), any(), any())).thenReturn(List.of());
		when(expenseService.projects(user)).thenReturn(List.of(new ProjectResponse(7L, "Acme Corp")));

		LocalDate from = LocalDate.of(2026, 7, 1);
		LocalDate to = LocalDate.of(2026, 7, 31);
		String text = textOf(service.exportPdf(user, from, to, 7L));

		verify(expenseService).findAll(eq(user), eq(from), eq(to), isNull(), eq(7L));
		assertThat(text).contains("Acme Corp");
		assertThat(text).contains("0 expenses");
		// An empty range is a valid report to send a client, not an error.
		assertThat(text).contains("Total (PHP)").contains("0.00");
	}

	@Test
	void namesTheWholeRangeWhenNoTagIsGiven() throws IOException {
		when(expenseService.findAll(any(), any(), any(), any(), any()))
				.thenReturn(List.of(expense(1L, "50.00", "Lunch", "Food")));

		String text = textOf(service.exportPdf(user, null, null, null));

		assertThat(text).contains("All time");
		assertThat(text).contains("Project / client: All");
		verify(expenseService, org.mockito.Mockito.never()).projects(any());
	}

	@Test
	void survivesTextTheStandardFontsCannotEncode() throws IOException {
		when(expenseService.findAll(any(), any(), any(), any(), any()))
				.thenReturn(List.of(expense(1L, "99.99", "Kape ☕ sa 東京", "Food")));

		String text = textOf(service.exportPdf(user, null, null, null));

		// The unencodable characters degrade to '?'; the amount, which is what has to reconcile,
		// is untouched.
		assertThat(text).contains("Kape");
		assertThat(text).contains("99.99");
	}

	@Test
	void paginatesRatherThanRunningOffTheFirstPage() throws IOException {
		List<ExpenseResponse> many = java.util.stream.IntStream.rangeClosed(1, 120)
				.mapToObj(i -> expense((long) i, "10.00", "Expense " + i, "Misc"))
				.toList();
		when(expenseService.findAll(any(), any(), any(), any(), any())).thenReturn(many);

		byte[] pdf = service.exportPdf(user, null, null, null);

		try (PDDocument document = Loader.loadPDF(pdf)) {
			assertThat(document.getNumberOfPages()).isGreaterThan(1);
			String text = new PDFTextStripper().getText(document);
			assertThat(text).contains("Expense 1 ", "Expense 120");
			assertThat(text).contains("1,200.00");
		}
	}

	private static ExpenseResponse expense(Long id, String amount, String description, String category) {
		BigDecimal value = new BigDecimal(amount).setScale(2, java.math.RoundingMode.HALF_UP);
		return new ExpenseResponse(id, value, category,
				LocalDateTime.of(2026, 8, 1, 9, 0).plusHours(id),
				description, "EXPENSE", false, "PHP",
				BigDecimal.ONE.setScale(6, java.math.RoundingMode.HALF_UP), value,
				ExpenseSource.MANUAL, null, null);
	}

	private static String textOf(byte[] pdf) throws IOException {
		assertThat(pdf).isNotEmpty();
		try (PDDocument document = Loader.loadPDF(pdf)) {
			return new PDFTextStripper().getText(document);
		}
	}
}
