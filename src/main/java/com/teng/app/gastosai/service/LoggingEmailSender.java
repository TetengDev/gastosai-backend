package com.teng.app.gastosai.service;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LoggingEmailSender implements EmailSender {

    @Override
    public void sendMagicLink(String toEmail, String link) {
        log.info("magic_link_issued to={} link={}", toEmail, link);
    }
}
