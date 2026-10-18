package com.gossiplog.chat.exception;

public class SelfTargetNotAllowedException extends RuntimeException {

    public SelfTargetNotAllowedException(String message) {
        super(message);
    }
}
