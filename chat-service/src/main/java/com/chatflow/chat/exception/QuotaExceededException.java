package com.chatflow.chat.exception;

/**
 * User-facing resource-cap / quota violation → HTTP 429 (see GlobalExceptionHandler).
 *
 * Throw this for "you've hit a limit, slow down" cases (e.g. the
 * MAX_PENDING_PER_USER scheduled-message cap). Do NOT reuse it — and do NOT
 * revert to {@link IllegalStateException} — for internal invariant/programming
 * failures (e.g. Result.value() on a Failure): those must surface as 500 via
 * the catch-all, not be mislabeled as a client 429.
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
