package com.teng.app.gastosai.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class InvalidSignatureException extends RuntimeException {

    public InvalidSignatureException() {
        super("Invalid webhook signature");
    }
}
