package com.teng.app.gastosai;

import com.teng.app.gastosai.config.EmailSenderConfig;
import com.teng.app.gastosai.service.JavaMailEmailSender;
import com.teng.app.gastosai.service.LoggingEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class EmailSenderConfigTest {

    private final EmailSenderConfig config = new EmailSenderConfig();

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        org.mockito.Mockito.when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    @Test
    void blankHost_usesLoggingSender_evenWhenMailSenderBeanExists() {
        var sender = config.emailSender(provider(mock(JavaMailSender.class)), "");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void whitespaceHost_usesLoggingSender() {
        var sender = config.emailSender(provider(mock(JavaMailSender.class)), "   ");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void noMailSenderBean_usesLoggingSender() {
        var sender = config.emailSender(provider(null), "smtp.gmail.com");
        assertThat(sender).isInstanceOf(LoggingEmailSender.class);
    }

    @Test
    void configuredHostAndMailSender_usesJavaMailSender() {
        var sender = config.emailSender(provider(mock(JavaMailSender.class)), "smtp.gmail.com");
        assertThat(sender).isInstanceOf(JavaMailEmailSender.class);
    }
}
