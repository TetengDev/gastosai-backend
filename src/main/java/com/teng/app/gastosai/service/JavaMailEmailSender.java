package com.teng.app.gastosai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

@Slf4j
@RequiredArgsConstructor
public class JavaMailEmailSender implements EmailSender {

    private final JavaMailSender mailSender;

    @Value("${gastos.mail.from:no-reply@gastosai.app}")
    private String from;

    @Override
    public void sendMagicLink(String toEmail, String link) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject("Your GastosAI sign-in link");
        message.setText("""
                Click the link below to sign in. It expires in 15 minutes and can only be used once.

                %s

                If you did not request this, you can safely ignore this email.""".formatted(link));
        mailSender.send(message);
        log.info("magic_link_sent to={}", toEmail);
    }

    @Override
    public void sendNotification(String toEmail, String subject, String body) {
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(toEmail);
        message.setSubject(subject);
        message.setText(body);
        mailSender.send(message);
        log.info("notification_sent to={}", toEmail);
    }
}
