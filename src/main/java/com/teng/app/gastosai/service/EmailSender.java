package com.teng.app.gastosai.service;

public interface EmailSender {

    void sendMagicLink(String toEmail, String link);

    /** Plain notification email (e.g. a new contact/feedback submission). Best-effort. */
    void sendNotification(String toEmail, String subject, String body);
}
