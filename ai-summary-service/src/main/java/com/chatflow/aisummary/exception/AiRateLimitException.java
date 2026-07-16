package com.chatflow.aisummary.exception;

/**
 * AI provider call-rate limit exceeded -> HTTP 429.
 *
 * Throw ONLY for the Bucket4j rate-limit gate; other failures must surface
 * as 500, not be masked as 429.
 */
public class AiRateLimitException extends RuntimeException {

    public AiRateLimitException(String message) {
        super(message);
    }
}
