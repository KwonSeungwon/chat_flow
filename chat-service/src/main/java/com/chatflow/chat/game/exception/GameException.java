package com.chatflow.chat.game.exception;

import lombok.Getter;

/** Game error with an HTTP status and a stable machine-readable code. */
@Getter
public class GameException extends RuntimeException {

    private final int status;
    private final String code;

    public GameException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static GameException validation(String message) {
        return new GameException(400, "VALIDATION_ERROR", message);
    }
}
