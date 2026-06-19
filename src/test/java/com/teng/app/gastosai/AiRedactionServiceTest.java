package com.teng.app.gastosai;

import com.teng.app.gastosai.service.AiRedactionService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AiRedactionServiceTest {

    private final AiRedactionService redaction = new AiRedactionService();

    @Test
    void masksVisaCardNumber() {
        String result = redaction.redact("card 4111111111111111 paid");
        assertThat(result).contains("[REDACTED_NUMBER]").doesNotContain("4111111111111111");
    }

    @Test
    void masksSpacedCardNumber() {
        String result = redaction.redact("4111 1111 1111 1111");
        assertThat(result).contains("[REDACTED_NUMBER]");
    }

    @Test
    void masksEmail() {
        String result = redaction.redact("contact user@example.com for details");
        assertThat(result).contains("[REDACTED_EMAIL]").doesNotContain("user@example.com");
    }

    @Test
    void masksPHMobileWithPlus63() {
        String result = redaction.redact("call +63 912 345 6789 now");
        assertThat(result).contains("[REDACTED_PHONE]");
    }

    @Test
    void masksPHMobileWithLeadingZero() {
        String result = redaction.redact("09123456789 is my number");
        assertThat(result).contains("[REDACTED_PHONE]");
    }

    @Test
    void leavesNormalTextIntact() {
        String input = "bought groceries for ₱350 at the mall";
        assertThat(redaction.redact(input)).isEqualTo(input);
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(redaction.redact(null)).isNull();
    }

    @Test
    void blankInputReturnsBlank() {
        assertThat(redaction.redact("   ")).isEqualTo("   ");
    }

    @Test
    void masksMultipleEmailsInOneString() {
        String result = redaction.redact("from a@b.com to c@d.org");
        assertThat(result).doesNotContain("a@b.com").doesNotContain("c@d.org");
    }
}
