package com.gossiplog.chat.exception;

public class SelfReportNotAllowedException extends RuntimeException {

    public SelfReportNotAllowedException(String message) {
        super(message);
    }
}
