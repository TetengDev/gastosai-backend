package com.teng.app.gastosai.service;

public interface EmailSender {

    void sendMagicLink(String toEmail, String link);
}
