package com.teng.app.gastosai.config;

import com.teng.app.gastosai.service.EmailSender;
import com.teng.app.gastosai.service.JavaMailEmailSender;
import com.teng.app.gastosai.service.LoggingEmailSender;
import com.teng.app.gastosai.service.ResendEmailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class EmailSenderConfig {

    /**
     * Email transport selection, in priority order:
     * <ol>
     *   <li><b>Resend</b> (HTTP API over 443) when {@code RESEND_API_KEY} is set — required on PaaS
     *       free tiers (e.g. Render) that block outbound SMTP ports.</li>
     *   <li><b>SMTP</b> via JavaMail when {@code spring.mail.host} is a non-blank value. (The host
     *       defaults to an empty string, which is still a *present* property and would otherwise make
     *       Spring build a JavaMailSender that sends to nothing — hence the explicit blank check.)</li>
     *   <li><b>Logging</b> fallback — dev / unconfigured deployments see the link in the server log.</li>
     * </ol>
     */
    @Bean
    public EmailSender emailSender(ObjectProvider<JavaMailSender> mailSenderProvider,
                                   @Value("${gastos.mail.resend.api-key:}") String resendApiKey,
                                   @Value("${gastos.mail.from:no-reply@gastosai.app}") String from,
                                   @Value("${spring.mail.host:}") String mailHost) {
        if (resendApiKey != null && !resendApiKey.isBlank()) {
            return new ResendEmailSender(resendApiKey, from);
        }
        JavaMailSender mailSender = mailSenderProvider.getIfAvailable();
        if (mailSender != null && mailHost != null && !mailHost.isBlank()) {
            return new JavaMailEmailSender(mailSender);
        }
        return new LoggingEmailSender();
    }
}
