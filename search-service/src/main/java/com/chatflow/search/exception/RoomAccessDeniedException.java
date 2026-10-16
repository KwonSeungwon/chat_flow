package com.chatflow.search.exception;

/** Caller is not a member of the requested room → 403. */
public class RoomAccessDeniedException extends RuntimeException {
    public RoomAccessDeniedException(String message) {
        super(message);
    }
}
