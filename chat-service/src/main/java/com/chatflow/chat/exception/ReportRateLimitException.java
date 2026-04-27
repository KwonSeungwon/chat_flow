package com.chatflow.chat.exception;

public class ReportRateLimitException extends RuntimeException {

    public ReportRateLimitException(String message) {
        super(message);
    }
}
