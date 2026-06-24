package com.teng.app.gastosai;

import com.teng.app.gastosai.config.EmailSenderConfig;
import com.teng.app.gastosai.service.JavaMailEmailSender;
import com.teng.app.gastosai.service.LoggingEmailSender;
import com.teng.app.gastosai.service.ResendEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmailSenderConfigTest {

    private final EmailSenderConfig config = new EmailSenderConfig();
    private static final String FROM = "no-reply@gastosai.app";

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    @Test
    void resendApiKeySet_usesResend_overSmtp() {
        var sender = config.emailSender(provider(mock(JavaMailSender.class)), "re_test_key", FROM, "smtp.gmail.com");
        assertThat(sender).isInstanceOf(ResendEmailSender.class);
    }

    @Test
    void blankHost_noResend_usesLoggingSender_evenWhenMailSenderBeanExists() {
        var sender = config.emailSender(provider(mock(JavaMailSender.class)), "", FROM, "");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void whitespaceHost_noResend_usesLoggingSender() {
        var sender = config.emailSender(provider(mock(JavaMailSender.class)), "", FROM, "   ");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void noMailSenderBean_noResend_usesLoggingSender() {
        var sender = config.emailSender(provider(null), "", FROM, "smtp.gmail.com");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void configuredHostAndMailSender_noResend_usesJavaMailSender() {
        var sender = config.emailSender(provider(mock(JavaMailSender.class)), "", FROM, "smtp.gmail.com");
        assertThat(sender).isInstanceOf(JavaMailEmailSender.class);
    }
}
