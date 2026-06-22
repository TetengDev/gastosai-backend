package com.teng.app.gastosai.config;

import com.teng.app.gastosai.service.EmailSender;
import com.teng.app.gastosai.service.JavaMailEmailSender;
import com.teng.app.gastosai.service.LoggingEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class EmailSenderConfig {

    /**
     * Spring Boot creates a JavaMailSender bean whenever {@code spring.mail.host} is *present*,
     * and our property defaults to an empty string ({@code ${MAIL_HOST:}}) so it is always present.
     * An empty host would make JavaMail attempt SMTP against nothing and silently drop magic links.
     * So we only use real SMTP when the host is actually configured; otherwise we log the link
     * (dev / unconfigured deployments still see the link in the server log).
     */
    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                   @Value("${spring.mail.host:}") String mailHost) {
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender != null && mailHost != null && !mailHost.isBlank()) {
            return new JavaMailEmailSender(mailSender);
        }
        return new LoggingEmailSender();
    }
}
