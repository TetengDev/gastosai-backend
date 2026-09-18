package com.teng.app.gastosai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.teng.app.gastosai.ai.AiFeature;
import com.teng.app.gastosai.ai.LlmResult;
import com.teng.app.gastosai.ai.LlmUsage;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.entity.AiUsageStatus;
import com.teng.app.gastosai.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class VisionService {

	private static final Set<String> ALLOWED_MEDIA_TYPES = Set.of(
			"image/jpeg", "image/jpg", "image/png", "image/gif", "image/webp",
			"image/heic", "image/heif", "image/bmp", "image/tiff"
	);

	/**
	 * Pixel budget an uploaded receipt is reduced to before it is base64-encoded.
	 *
	 * <p>1568 px on the long edge and ~1.15 megapixels of area are the numbers Anthropic publishes
	 * as the point past which its vision API resizes the image itself. Anything above them is
	 * uploaded, held in memory, and then thrown away by the provider before the model ever sees
	 * it. A current phone camera posts 4032x3024 — 12.2 MP, about ten times this budget — so the
	 * whole of that excess is waste. OpenAI's high-detail path has the same shape (fit to
	 * 2048x2048, then the short edge to 768, then tile), so one budget serves both adapters.
	 *
	 * <p>The budget is deliberately set <em>at</em> the providers' own ceiling rather than below
	 * it: at this size the model receives exactly the pixels it would have received anyway, so the
	 * change cannot alter parse accuracy. Trading resolution for tokens below this line is a
	 * separate, measurable decision and is not made here.
	 */
	public static final int MAX_EDGE_PX = 1568;

	public static final long MAX_AREA_PX = 1_150_000L;

	/**
	 * Hard ceiling on the pixels a header may declare before the upload is refused outright.
	 *
	 * <p>100 MP is roughly eight times the largest phone sensor sold today, so no real receipt photo
	 * comes near it, while a decompression bomb clears it by orders of magnitude.
	 */
	public static final long MAX_DECODE_PX = 100_000_000L;

	/**
	 * Result of preparing an upload: the base64 payload and the media type that actually describes
	 * it, which differs from the uploaded one when the image was re-encoded as JPEG.
	 */
	public record EncodedImage(String base64, String mediaType) {
	}

	private static final String SYSTEM_PROMPT_TEMPLATE = """
			You are GastosAI, an AI receipt parser for a Filipino expense tracker.
			Analyze the image and return ONLY a raw JSON object — no markdown fences, no explanation.
			The JSON must match this exact shape:
			{"amount":250.00,"category":"Food","date":"2026-06-11T00:00:00","description":"Jollibee lunch","confidence":"high","saveable":true,"hint":null,"rejectionMessage":null}
			Fields: amount (number or null), category (string or null), date (ISO-8601 LocalDateTime or null),
			description (string or null), confidence ("high"/"medium"/"low"), saveable (boolean), hint (string or null), rejectionMessage (string or null).
			If the image is a receipt/bill/financial document: populate amount, category, date, description; set saveable=true; hint=null; rejectionMessage=null.
			If the image is NOT expense-related: set saveable=false, hint=<brief description of what you see>, other fields null.
			Chat mode: %s — plain=clear friendly English, professional=formal business tone, genz=casual Gen Z slang with emojis.
			When saveable=false: populate rejectionMessage with a short mode-appropriate message telling the user this is not a receipt and to attach one instead.
			""";

	private final RestClient claudeRestClient;
	private final RestClient openAiRestClient;
	private final AiProviderProperties providerProps;
	private final ClaudeProperties claudeProperties;
	private final OpenAiProperties openAiProperties;
	private final ObjectMapper objectMapper;
	private final AiQuotaService aiQuotaService;
	private final AiUsageService aiUsageService;
	private final AiManagedProperties aiManagedProperties;
	private final AiRedactionService aiRedactionService;

	public ParsedExpenseResult analyze(String question, MultipartFile file, String mode) throws IOException {
		return analyze(question, file, mode, null);
	}

	public ParsedExpenseResult analyze(String question, MultipartFile file, String mode, User user) throws IOException {
		String contentType = file.getContentType();
		if (contentType == null || !ALLOWED_MEDIA_TYPES.contains(contentType.toLowerCase())) {
			throw new IllegalArgumentException(
					"Unsupported file type '" + contentType + "'. Upload a JPEG, PNG, GIF, WebP, or HEIC image.");
		}

		if (user != null) {
			aiQuotaService.assertWithinQuota(user, AiFeature.RECEIPT_ANALYSIS);
		}

		String prompt = (question != null && !question.isBlank())
				? question
				: "Extract expense information from this image.";
		// Mask any PII (card/email/phone) the user typed into the instruction before it reaches the LLM.
		prompt = aiRedactionService.redact(prompt);
		int max = aiManagedProperties.getMaxPromptChars();
		if (prompt.length() > max) {
			prompt = prompt.substring(0, max);
		}

		String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, mode != null ? mode : "plain");

		// Inside the try: decoding an untrusted image is work this backend pays for whether or not
		// a provider call follows, so a failure there has to count against the user's cap too.
		try {
			EncodedImage encoded = encodeForVision(file.getBytes(), contentType);
			String base64 = encoded.base64();
			String mediaType = encoded.mediaType();
			LlmResult<ParsedExpenseResult> result = "claude".equalsIgnoreCase(providerProps.getProvider())
					? callClaude(prompt, base64, mediaType, systemPrompt)
					: callOpenAi(prompt, base64, mediaType, systemPrompt);
			if (user != null) {
				LlmUsage usage = result.usage();
				aiUsageService.record(user.getId(), providerProps.getProvider(),
						resolveModel(), AiFeature.RECEIPT_ANALYSIS,
						usage.inputTokens(), usage.outputTokens(), AiUsageStatus.SUCCESS, null);
			}
			return result.value();
		} catch (Throwable t) {
			// Throwable, not Exception: an OutOfMemoryError raised while decoding a hostile image
			// is an Error, and letting it escape unrecorded would leave the absolute monthly cap —
			// the valve that throttles exactly that kind of repeated abuse — none the wiser.
			if (user != null) {
				try {
					aiUsageService.record(user.getId(), providerProps.getProvider(),
							resolveModel(), AiFeature.RECEIPT_ANALYSIS,
							null, null, AiUsageStatus.FAILED, t.getClass().getSimpleName());
				} catch (Exception recordFailure) {
					t.addSuppressed(recordFailure);
				}
			}
			throw t;
		}
	}

	/**
	 * Base64-encodes an upload, downscaling it to {@link #MAX_EDGE_PX} / {@link #MAX_AREA_PX}
	 * first if it is over budget.
	 *
	 * <p>Fails open: an image ImageIO cannot decode (HEIC/HEIF have no bundled reader) or cannot
	 * re-encode is sent exactly as uploaded, because a receipt that reaches the model at full size
	 * is only expensive, while one that fails to reach it at all is a broken scan.
	 */
	public static EncodedImage encodeForVision(byte[] original, String mediaType) {
		int[] dimensions = readDimensions(original);
		if (dimensions == null) {
			return asUploaded(original, mediaType);
		}
		int width = dimensions[0];
		int height = dimensions[1];

		// Fails closed, unlike everything else here. The header is attacker-controlled and a
		// low-entropy PNG well under the 10 MB upload cap can declare a multi-gigapixel canvas;
		// honouring it would allocate the raster before any budget applies. Refusing one absurd
		// upload is better than an OutOfMemoryError that takes the API down for every tenant.
		if ((long) width * height > MAX_DECODE_PX) {
			throw new IllegalArgumentException("Image is too large to process: " + width + "x" + height
					+ " pixels. Upload a photo of the receipt rather than a full-resolution scan.");
		}

		double scale = scaleFor(width, height);
		if (scale >= 1.0) {
			return asUploaded(original, mediaType);
		}

		// Floor, not round: rounding up can push the area back over the budget it was solved for.
		int targetWidth = Math.max(1, (int) Math.floor(width * scale));
		int targetHeight = Math.max(1, (int) Math.floor(height * scale));

		BufferedImage source = decode(original, targetWidth, targetHeight);
		if (source == null) {
			return asUploaded(original, mediaType);
		}
		// Re-encoding drops the EXIF orientation tag the original carried, so bake the rotation
		// into the pixels instead; a sideways receipt is a parse failure, not a cosmetic issue.
		BufferedImage scaled = applyOrientation(resample(source, targetWidth, targetHeight),
				exifOrientation(original));

		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			// JPEG regardless of what was uploaded: a photographed receipt is continuous-tone, so
			// PNG at this size costs several times the bytes for no visible difference.
			if (!ImageIO.write(scaled, "jpeg", out)) {
				return asUploaded(original, mediaType);
			}
			return new EncodedImage(Base64.getEncoder().encodeToString(out.toByteArray()), "image/jpeg");
		} catch (IOException e) {
			return asUploaded(original, mediaType);
		}
	}

	private static EncodedImage asUploaded(byte[] original, String mediaType) {
		return new EncodedImage(Base64.getEncoder().encodeToString(original),
				mediaType != null ? mediaType : "image/jpeg");
	}

	/** Width and height straight from the image header, without decoding a pixel. */
	private static int[] readDimensions(byte[] bytes) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) {
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				return new int[]{reader.getWidth(0), reader.getHeight(0)};
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * Decodes at a subsampled resolution rather than in full.
	 *
	 * <p>This is the memory bound as well as a speed one: the heap a single upload can claim becomes
	 * a function of the budget rather than of what the uploader declared. Subsampling is
	 * nearest-neighbour, so it stops while each edge is still at least twice the target and leaves
	 * the last factor of two to the bilinear pass below. Because the step is a power of two, an edge
	 * can be left anywhere in [2x, 4x) of its target — so the decoded raster is at most sixteen times
	 * the target area, ~18 MP, and {@link #MAX_DECODE_PX} caps it regardless.
	 */
	private static BufferedImage decode(byte[] bytes, int targetWidth, int targetHeight) {
		try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
			if (input == null) {
				return null;
			}
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				int step = subsamplingFor(reader.getWidth(0), reader.getHeight(0), targetWidth, targetHeight);
				ImageReadParam param = reader.getDefaultReadParam();
				if (step > 1) {
					param.setSourceSubsampling(step, step, 0, 0);
				}
				return reader.read(0, param);
			} finally {
				reader.dispose();
			}
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static int subsamplingFor(int width, int height, int targetWidth, int targetHeight) {
		int step = 1;
		while (width / (step * 2) >= targetWidth * 2 && height / (step * 2) >= targetHeight * 2) {
			step *= 2;
		}
		return step;
	}

	private static double scaleFor(int width, int height) {
		double byEdge = (double) MAX_EDGE_PX / Math.max(width, height);
		double byArea = Math.sqrt((double) MAX_AREA_PX / ((long) width * height));
		return Math.min(1.0, Math.min(byEdge, byArea));
	}

	/**
	 * Halves the image repeatedly before the final step. A single bilinear pass over a 10x
	 * reduction reads four source pixels per output pixel and ignores the other ninety-odd, which
	 * on receipt text aliases digits into noise; halving keeps every pixel contributing.
	 */
	private static BufferedImage resample(BufferedImage source, int targetWidth, int targetHeight) {
		BufferedImage current = source;
		int width = source.getWidth();
		int height = source.getHeight();
		while (width / 2 > targetWidth && height / 2 > targetHeight) {
			width /= 2;
			height /= 2;
			current = draw(current, width, height);
		}
		return draw(current, targetWidth, targetHeight);
	}

	private static BufferedImage draw(BufferedImage source, int width, int height) {
		BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			// TYPE_INT_RGB carries no alpha, so flatten onto white first: a transparent PNG
			// receipt would otherwise arrive with black where its background was.
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, width, height);
			g.drawImage(source, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	/**
	 * Reads the EXIF Orientation tag (1-8) out of a JPEG, or 1 if there is none.
	 *
	 * <p>Parsed by hand rather than by adding a metadata dependency: it is one tag in the first IFD,
	 * and every offset below is bounds-checked because the bytes are an untrusted upload.
	 */
	public static int exifOrientation(byte[] bytes) {
		if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
			return 1;
		}
		int at = 2;
		while (at + 4 <= bytes.length) {
			if ((bytes[at] & 0xFF) != 0xFF) {
				return 1;
			}
			int marker = bytes[at + 1] & 0xFF;
			if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
				at += 2;
				continue;
			}
			// Start of scan or end of image: EXIF, if it existed, would have come first.
			if (marker == 0xDA || marker == 0xD9) {
				return 1;
			}
			int length = ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
			if (length < 2 || at + 2 + length > bytes.length) {
				return 1;
			}
			if (marker == 0xE1 && length >= 16
					&& bytes[at + 4] == 'E' && bytes[at + 5] == 'x'
					&& bytes[at + 6] == 'i' && bytes[at + 7] == 'f') {
				return orientationFromTiff(bytes, at + 10, at + 2 + length);
			}
			at += 2 + length;
		}
		return 1;
	}

	private static int orientationFromTiff(byte[] bytes, int start, int end) {
		if (start + 8 > end) {
			return 1;
		}
		boolean bigEndian = (bytes[start] & 0xFF) == 0x4D;
		long ifdOffset = readUnsigned(bytes, start + 4, 4, bigEndian);
		long ifd = start + ifdOffset;
		if (ifd < start || ifd + 2 > end) {
			return 1;
		}
		int entries = (int) readUnsigned(bytes, (int) ifd, 2, bigEndian);
		for (int i = 0; i < entries; i++) {
			long entry = ifd + 2 + (long) i * 12;
			if (entry + 12 > end) {
				return 1;
			}
			if (readUnsigned(bytes, (int) entry, 2, bigEndian) == 0x0112) {
				long value = readUnsigned(bytes, (int) entry + 8, 2, bigEndian);
				return (value >= 1 && value <= 8) ? (int) value : 1;
			}
		}
		return 1;
	}

	private static long readUnsigned(byte[] bytes, int offset, int length, boolean bigEndian) {
		long value = 0;
		for (int i = 0; i < length; i++) {
			int index = bigEndian ? offset + i : offset + length - 1 - i;
			value = (value << 8) | (bytes[index] & 0xFF);
		}
		return value;
	}

	/** Applies an EXIF orientation to the pixels; 5-8 swap the two edges. */
	private static BufferedImage applyOrientation(BufferedImage image, int orientation) {
		if (orientation <= 1 || orientation > 8) {
			return image;
		}
		int width = image.getWidth();
		int height = image.getHeight();
		boolean transposed = orientation >= 5;
		BufferedImage out = new BufferedImage(transposed ? height : width,
				transposed ? width : height, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		try {
			// Calls apply to the source point in reverse order, innermost last.
			AffineTransform transform = new AffineTransform();
			switch (orientation) {
				case 2 -> {
					transform.translate(width, 0);
					transform.scale(-1, 1);
				}
				case 3 -> {
					transform.translate(width, height);
					transform.rotate(Math.PI);
				}
				case 4 -> {
					transform.translate(0, height);
					transform.scale(1, -1);
				}
				case 5 -> {
					transform.rotate(Math.PI / 2);
					transform.scale(1, -1);
				}
				case 6 -> {
					transform.translate(height, 0);
					transform.rotate(Math.PI / 2);
				}
				case 7 -> {
					transform.translate(height, width);
					transform.rotate(Math.PI);
					transform.rotate(Math.PI / 2);
					transform.scale(1, -1);
				}
				default -> {
					transform.translate(0, width);
					transform.rotate(-Math.PI / 2);
				}
			}
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(image, transform, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	private String resolveModel() {
		return "claude".equalsIgnoreCase(providerProps.getProvider())
				? claudeProperties.getModel()
				: openAiProperties.getModel();
	}

	private LlmResult<ParsedExpenseResult> callClaude(String prompt, String base64, String mediaType, String systemPrompt) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", claudeProperties.getModel());
		body.put("max_tokens", 1024);
		body.put("system", systemPrompt);

		ArrayNode messages = body.putArray("messages");
		ObjectNode userMsg = messages.addObject();
		userMsg.put("role", "user");
		ArrayNode content = userMsg.putArray("content");

		ObjectNode imgBlock = content.addObject();
		imgBlock.put("type", "image");
		ObjectNode source = imgBlock.putObject("source");
		source.put("type", "base64");
		source.put("media_type", mediaType);
		source.put("data", base64);

		ObjectNode textBlock = content.addObject();
		textBlock.put("type", "text");
		textBlock.put("text", prompt);

		String raw = claudeRestClient.post()
				.uri("/messages")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		try {
			JsonNode root = objectMapper.readTree(raw);
			String text = root.path("content").path(0).path("text").asText("").trim();
			LlmUsage usage = extractClaudeUsage(root);
			return LlmResult.of(parseResult(text), usage);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse Claude vision response", e);
		}
	}

	private LlmResult<ParsedExpenseResult> callOpenAi(String prompt, String base64, String mediaType, String systemPrompt) {
		ObjectNode body = objectMapper.createObjectNode();
		body.put("model", openAiProperties.getModel());
		body.put("max_completion_tokens", 1024);

		ArrayNode messages = body.putArray("messages");
		ObjectNode system = messages.addObject();
		system.put("role", "system");
		system.put("content", systemPrompt);

		ObjectNode userMsg = messages.addObject();
		userMsg.put("role", "user");
		ArrayNode content = userMsg.putArray("content");

		ObjectNode imgBlock = content.addObject();
		imgBlock.put("type", "image_url");
		imgBlock.putObject("image_url").put("url", "data:" + mediaType + ";base64," + base64);

		ObjectNode textBlock = content.addObject();
		textBlock.put("type", "text");
		textBlock.put("text", prompt);

		String raw = openAiRestClient.post()
				.uri("/v1/chat/completions")
				.contentType(MediaType.APPLICATION_JSON)
				.body(body.toString())
				.retrieve()
				.body(String.class);

		try {
			JsonNode root = objectMapper.readTree(raw);
			String text = root.path("choices").path(0).path("message").path("content").asText("").trim();
			LlmUsage usage = extractOpenAiUsage(root);
			return LlmResult.of(parseResult(text), usage);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to parse OpenAI vision response", e);
		}
	}

	private static LlmUsage extractClaudeUsage(JsonNode root) {
		try {
			JsonNode u = root.path("usage");
			if (u.isMissingNode() || u.isNull()) return LlmUsage.absent();
			JsonNode in = u.path("input_tokens");
			JsonNode out = u.path("output_tokens");
			return new LlmUsage(in.isNumber() ? in.intValue() : null, out.isNumber() ? out.intValue() : null);
		} catch (Exception e) {
			return LlmUsage.absent();
		}
	}

	private static LlmUsage extractOpenAiUsage(JsonNode root) {
		try {
			JsonNode u = root.path("usage");
			if (u.isMissingNode() || u.isNull()) return LlmUsage.absent();
			JsonNode in = u.path("prompt_tokens");
			JsonNode out = u.path("completion_tokens");
			return new LlmUsage(in.isNumber() ? in.intValue() : null, out.isNumber() ? out.intValue() : null);
		} catch (Exception e) {
			return LlmUsage.absent();
		}
	}

	private ParsedExpenseResult parseResult(String rawText) {
		String cleaned = rawText
				.replaceAll("(?s)^```json\\s*", "")
				.replaceAll("(?s)^```\\s*", "")
				.replaceAll("(?s)\\s*```$", "")
				.trim();
		try {
			return objectMapper.readValue(cleaned, ParsedExpenseResult.class);
		} catch (JsonProcessingException e) {
			return new ParsedExpenseResult(null, null, null, null, "low", false, rawText, null);
		}
	}
}
