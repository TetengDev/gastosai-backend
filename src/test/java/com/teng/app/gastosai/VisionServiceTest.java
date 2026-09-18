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
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        Random random = new Random(7);
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

    /** The 34-byte APP1 segment a phone writes when it holds the camera sideways. */
    private static byte[] withExifOrientation(byte[] jpeg, int orientation) {
        byte[] app1 = new byte[]{
            (byte) 0xFF, (byte) 0xE1, 0x00, 0x22,
            'E', 'x', 'i', 'f', 0x00, 0x00,
            'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08,   // big-endian TIFF header, IFD0 at 8
            0x00, 0x01,                                      // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01,  // tag 0x0112, SHORT, count 1
            0x00, (byte) orientation, 0x00, 0x00,            // value, left-aligned in 4 bytes
            0x00, 0x00, 0x00, 0x00                           // no next IFD
        };
        byte[] out = new byte[jpeg.length + app1.length];
        System.arraycopy(jpeg, 0, out, 0, 2);                // SOI
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }

    /** Mean brightness of a small patch, used to find where a corner mark ended up. */
    private static double brightness(BufferedImage image, int left, int top) {
        long sum = 0;
        int size = 16;
        for (int y = top; y < top + size; y++) {
            for (int x = left; x < left + size; x++) {
                sum += image.getRGB(x, y) & 0xFF;
            }
        }
        return (double) sum / (size * size);
    }

    @Test
    void exifOrientationIsBakedIntoThePixels() throws Exception {
        BufferedImage landscape = ImageIO.read(new ByteArrayInputStream(receiptPng(4032, 3024)));
        // A black mark in the top-left corner: orientation 6 is a 90° clockwise turn, so it must
        // come back in the top-right. A mirrored transform would put it in the top-left instead.
        Graphics2D mark = landscape.createGraphics();
        mark.setColor(Color.BLACK);
        mark.fillRect(0, 0, 800, 600);
        mark.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(landscape, "jpeg", jpeg);
        // Orientation 6: the sensor read landscape, the receipt is meant to be seen rotated 90° CW.
        byte[] original = withExifOrientation(jpeg.toByteArray(), 6);
        assertThat(VisionService.exifOrientation(original)).isEqualTo(6);

        VisionService.EncodedImage encoded = VisionService.encodeForVision(original, "image/jpeg");

        BufferedImage sent = decodeBase64(encoded.base64());
        assertThat(sent.getHeight()).isGreaterThan(sent.getWidth());
        assertThat(brightness(sent, sent.getWidth() - 20, 4)).isLessThan(64);
        assertThat(brightness(sent, 4, 4)).isGreaterThan(192);
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isLessThanOrEqualTo(VisionService.MAX_EDGE_PX);
        assertThat((long) sent.getWidth() * sent.getHeight()).isLessThanOrEqualTo(VisionService.MAX_AREA_PX);
    }

    @Test
    void imageWithoutExif_keepsItsOrientation() throws Exception {
        byte[] original = receiptPng(4032, 3024);

        assertThat(VisionService.exifOrientation(original)).isEqualTo(1);
        BufferedImage sent = decodeBase64(VisionService.encodeForVision(original, "image/png").base64());
        assertThat(sent.getWidth()).isGreaterThan(sent.getHeight());
    }

    /** A PNG whose header declares a canvas far larger than its compressed bytes could ever hold. */
    private static byte[] declaredSize(byte[] png, int width, int height) {
        byte[] out = png.clone();
        for (int i = 0; i < 4; i++) {
            out[16 + i] = (byte) (width >>> (24 - 8 * i));
            out[20 + i] = (byte) (height >>> (24 - 8 * i));
        }
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(out, 12, 17); // chunk type + IHDR payload
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) {
            out[29 + i] = (byte) (value >>> (24 - 8 * i));
        }
        return out;
    }

    @Test
    void decompressionBomb_isRefusedBeforeAnyPixelIsDecoded() throws Exception {
        // 30000x30000 = 900 MP, which would be a 3.6 GB raster, from a few hundred bytes on the wire.
        byte[] bomb = declaredSize(receiptPng(64, 64), 30000, 30000);

        assertThatThrownBy(() -> VisionService.encodeForVision(bomb, "image/png"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too large");
    }

    @Test
    void transposedExifOrientationIsAppliedAlongTheRightDiagonal() throws Exception {
        BufferedImage landscape = ImageIO.read(new ByteArrayInputStream(receiptPng(4032, 3024)));
        // Mark the top-right. Orientation 5 transposes along the main diagonal, so (x,y) -> (y,x)
        // and the mark belongs in the bottom-left; a wrong sign would put it in the top-left.
        Graphics2D mark = landscape.createGraphics();
        mark.setColor(Color.BLACK);
        mark.fillRect(4032 - 800, 0, 800, 600);
        mark.dispose();
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(landscape, "jpeg", jpeg);
        byte[] original = withExifOrientation(jpeg.toByteArray(), 5);

        BufferedImage sent = decodeBase64(VisionService.encodeForVision(original, "image/jpeg").base64());

        assertThat(sent.getHeight()).isGreaterThan(sent.getWidth());
        assertThat(brightness(sent, 4, sent.getHeight() - 20)).isLessThan(64);
        assertThat(brightness(sent, 4, 4)).isGreaterThan(192);
    }

    @Test
    void largeLowEntropyImage_goesThroughTheSubsampledDecode() throws Exception {
        // 27 MP of near-flat grey: a few hundred KB on the wire, well under the refusal ceiling, so
        // it is the case the subsampled decode — not the header check — has to survive.
        BufferedImage huge = new BufferedImage(6000, 4500, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = huge.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 6000, 4500);
        g.setColor(Color.DARK_GRAY);
        g.fillRect(500, 500, 5000, 200);
        g.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(huge, "png", png);

        VisionService.EncodedImage encoded = VisionService.encodeForVision(png.toByteArray(), "image/png");

        BufferedImage sent = decodeBase64(encoded.base64());
        assertThat(Math.max(sent.getWidth(), sent.getHeight())).isLessThanOrEqualTo(VisionService.MAX_EDGE_PX);
        assertThat((long) sent.getWidth() * sent.getHeight()).isLessThanOrEqualTo(VisionService.MAX_AREA_PX);
        assertThat(encoded.mediaType()).isEqualTo("image/jpeg");
    }

    @Test
    void undecodableUpload_isSentAsUploaded() {
        byte[] heic = "not an image ImageIO can read".getBytes();

        VisionService.EncodedImage encoded = VisionService.encodeForVision(heic, "image/heic");

        assertThat(encoded.mediaType()).isEqualTo("image/heic");
        assertThat(Base64.getDecoder().decode(encoded.base64())).isEqualTo(heic);
    }
}
