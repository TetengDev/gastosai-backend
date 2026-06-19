package com.teng.app.gastosai.service;

import org.springframework.stereotype.Service;

import java.util.regex.Pattern;

@Service
public class AiRedactionService {

    private static final Pattern CARD_NUMBER = Pattern.compile(
            "\\b(?:\\d[ -]?){12,18}\\d\\b"
    );

    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}"
    );

    // PH mobile (+63 9xx or 09xx) and generic international formats
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+63|\\b0)[ -]?9\\d{2}[ -]?\\d{3}[ -]?\\d{4}\\b" +
            "|\\+\\d{1,3}[ -]?\\(?\\d{1,4}\\)?[ -]?\\d{3,4}[ -]?\\d{4}\\b"
    );

    public String redact(String input) {
        if (input == null || input.isBlank()) {
            return input;
        }
        String result = EMAIL.matcher(input).replaceAll("[REDACTED_EMAIL]");
        result = PHONE.matcher(result).replaceAll("[REDACTED_PHONE]");
        result = CARD_NUMBER.matcher(result).replaceAll("[REDACTED_NUMBER]");
        return result;
    }
}
