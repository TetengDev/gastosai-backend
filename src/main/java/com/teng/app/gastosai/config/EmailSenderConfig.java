package com.teng.app.gastosai.config;

import com.teng.app.gastosai.service.EmailSender;
import com.teng.app.gastosai.service.JavaMailEmailSender;
import com.teng.app.gastosai.service.LoggingEmailSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration
public class EmailSenderConfig {

    @Bean
    @ConditionalOnBean(JavaMailSender.class)
    public EmailSender javaMailEmailSender(JavaMailSender mailSender) {
        return new JavaMailEmailSender(mailSender);
    }

    @Bean
    @ConditionalOnMissingBean(JavaMailSender.class)
    public EmailSender loggingEmailSender() {
        return new LoggingEmailSender();
    }
}
