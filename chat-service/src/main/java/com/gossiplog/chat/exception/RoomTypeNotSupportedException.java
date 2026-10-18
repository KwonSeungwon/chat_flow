package com.gossiplog.chat.exception;

public class RoomTypeNotSupportedException extends RuntimeException {

    public RoomTypeNotSupportedException(String message) {
        super(message);
    }
}
