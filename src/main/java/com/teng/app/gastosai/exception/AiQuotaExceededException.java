package com.teng.app.gastosai.exception;

public class AiQuotaExceededException extends RuntimeException {

    public AiQuotaExceededException() {
        super("You've reached your monthly AI limit.");
    }
}
