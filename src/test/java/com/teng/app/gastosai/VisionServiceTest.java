package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.service.AiQuotaService;
import com.teng.app.gastosai.service.AiRedactionService;
import com.teng.app.gastosai.service.AiUsageService;
import com.teng.app.gastosai.service.VisionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionServiceTest {

    @Mock RestClient claudeRestClient;
    @Mock RestClient openAiRestClient;
    @Mock RestClient.RequestBodyUriSpec uriSpec;
    @Mock RestClient.RequestBodySpec bodySpec;
    @Mock RestClient.ResponseSpec responseSpec;
    @Mock AiQuotaService aiQuotaService;
    @Mock AiUsageService aiUsageService;

    AiProviderProperties providerProps;
    ClaudeProperties claudeProps;
    OpenAiProperties openAiProps;
    AiManagedProperties managedProps;
    ObjectMapper objectMapper;

    VisionService visionService;

    @BeforeEach
    void setUp() {
        providerProps = new AiProviderProperties();
        providerProps.setProvider("openai");

        claudeProps = new ClaudeProperties();
        openAiProps = new OpenAiProperties();
        managedProps = new AiManagedProperties();
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();

        visionService = new VisionService(
                claudeRestClient, openAiRestClient,
                providerProps, claudeProps, openAiProps, objectMapper,
                aiQuotaService, aiUsageService, managedProps, new AiRedactionService());
    }

    private void mockOpenAiChain(String responseJson) {
        when(openAiRestClient.post()).thenReturn(uriSpec);
        when(uriSpec.uri(anyString())).thenReturn(bodySpec);
        when(bodySpec.contentType(any(MediaType.class))).thenReturn(bodySpec);
        when(bodySpec.body(anyString())).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.body(String.class)).thenReturn(responseJson);
    }

    private MockMultipartFile whitePixel() {
        byte[] png = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x02, 0x00, 0x00, 0x00, (byte) 0x90, 0x77, 0x53,
            (byte) 0xDE, 0x00, 0x00, 0x00, 0x0C, 0x49, 0x44, 0x41,
            0x54, 0x08, (byte) 0xD7, 0x63, (byte) 0xF8, (byte) 0xFF, (byte) 0xFF, 0x3F,
            0x00, 0x05, (byte) 0xFE, 0x02, (byte) 0xFE, (byte) 0xDC, (byte) 0xCC, 0x59,
            (byte) 0xE7, 0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E,
            0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
        };
        return new MockMultipartFile("file", "test.png", "image/png", png);
    }

    @Test
    void receiptImage_returnsParseableResult() throws Exception {
        String openAiResponse = """
                {"choices":[{"message":{"content":"{\\"amount\\":250.00,\\"category\\":\\"Food\\",\\"date\\":\\"2026-06-11T00:00:00\\",\\"description\\":\\"Jollibee lunch\\",\\"confidence\\":\\"high\\",\\"saveable\\":true,\\"hint\\":null}"}}]}
                """;
        mockOpenAiChain(openAiResponse);

        ParsedExpenseResult result = visionService.analyze(null, whitePixel(), "plain");

        assertThat(result.saveable()).isTrue();
        assertThat(result.amount()).isNotNull();
    }

    @Test
    void nonReceiptImage_returnsUnsaveableResult() throws Exception {
        String openAiResponse = """
                {"choices":[{"message":{"content":"{\\"amount\\":null,\\"category\\":null,\\"date\\":null,\\"description\\":null,\\"confidence\\":\\"low\\",\\"saveable\\":false,\\"hint\\":\\"A photo of a cat\\"}"}}]}
                """;
        mockOpenAiChain(openAiResponse);

        ParsedExpenseResult result = visionService.analyze(null, whitePixel(), "plain");

        assertThat(result.saveable()).isFalse();
        assertThat(result.hint()).isEqualTo("A photo of a cat");
    }

    @Test
    void malformedAiResponse_returnsFallback() throws Exception {
        String openAiResponse = """
                {"choices":[{"message":{"content":"not valid json"}}]}
                """;
        mockOpenAiChain(openAiResponse);

        ParsedExpenseResult result = visionService.analyze(null, whitePixel(), "plain");

        assertThat(result.saveable()).isFalse();
        assertThat(result.hint()).contains("not valid json");
    }

    /** A receipt-shaped image generated in memory: white card, dark text bars, no binary fixture. */
    private static byte[] receiptPng(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.DARK_GRAY);
        for (int y = height / 10; y < height; y += height / 10) {
            g.fillRect(width / 8, y, width * 3 / 4, Math.max(1, height / 100));
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static BufferedImage decodeBase64(String base64) throws Exception {
        return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
    }

    @Test
    void oversizedImage_isDownscaledWithinTheBudget() throws Exception {
        byte[] original = receiptPng(4032, 3024);

        VisionService.EncodedImage encoded = VisionService.encodeForVision(original, "image/png");

        BufferedImage sent = decodeBase64(encoded.base64());
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isLessThanOrEqualTo(VisionService.MAX_EDGE_PX);
        assertThat((long) sent.getWidth() * sent.getHeight()).isLessThanOrEqualTo(VisionService.MAX_AREA_PX);
        // Aspect ratio is preserved, so the receipt is not stretched.
        assertThat((double) sent.getWidth() / sent.getHeight()).isCloseTo(4032d / 3024d, within(0.01));
        assertThat(encoded.mediaType()).isEqualTo("image/jpeg");
        // The synthetic receipt is flat colour, so its PNG is far smaller than a photograph's and
        // byte size proves nothing here; pixels sent is what the budget and the provider count.
        assertThat((long) sent.getWidth() * sent.getHeight()).isLessThan(4032L * 3024L);
    }

    @Test
    void withinBudgetImage_isPassedThroughUnchanged() throws Exception {
        byte[] original = receiptPng(1000, 1100);

        VisionService.EncodedImage encoded = VisionService.encodeForVision(original, "image/png");

        assertThat(encoded.mediaType()).isEqualTo("image/png");
        assertThat(Base64.getDecoder().decode(encoded.base64())).isEqualTo(original);
    }

    @Test
    void photographicUpload_shrinksThePayloadSent() throws Exception {
        // Sensor noise over the text bars: a flat synthetic image compresses unrealistically well,
        // so only a noisy one says anything about the bytes a real phone photo would upload.
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(receiptPng(4032, 3024)));
        java.util.Random random = new java.util.Random(7);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int noise = random.nextInt(24) - 12;
                int rgb = image.getRGB(x, y);
                int value = Math.clamp((rgb & 0xFF) + noise, 0, 255);
                image.setRGB(x, y, (value << 16) | (value << 8) | value);
            }
        }
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(image, "jpeg", jpeg);
        byte[] original = jpeg.toByteArray();

        VisionService.EncodedImage encoded = VisionService.encodeForVision(original, "image/jpeg");

        int sentBytes = Base64.getDecoder().decode(encoded.base64()).length;
        System.out.printf("[TEN-163] 4032x3024 jpeg %d bytes -> %d bytes (%.1f%% of original)%n",
                original.length, sentBytes, 100.0 * sentBytes / original.length);
        assertThat(sentBytes).isLessThan(original.length / 2);
    }

    @Test
    void undecodableUpload_isSentAsUploaded() {
        byte[] heic = "not an image ImageIO can read".getBytes();

        VisionService.EncodedImage encoded = VisionService.encodeForVision(heic, "image/heic");

        assertThat(encoded.mediaType()).isEqualTo("image/heic");
        assertThat(Base64.getDecoder().decode(encoded.base64())).isEqualTo(heic);
    }
}
