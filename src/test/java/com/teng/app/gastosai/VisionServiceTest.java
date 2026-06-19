package com.teng.app.gastosai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.teng.app.gastosai.config.AiManagedProperties;
import com.teng.app.gastosai.config.AiProviderProperties;
import com.teng.app.gastosai.config.ClaudeProperties;
import com.teng.app.gastosai.config.OpenAiProperties;
import com.teng.app.gastosai.dto.ParsedExpenseResult;
import com.teng.app.gastosai.service.AiQuotaService;
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

import static org.assertj.core.api.Assertions.assertThat;
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
                aiQuotaService, aiUsageService, managedProps);
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
}
