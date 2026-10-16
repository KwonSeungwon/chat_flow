package com.chatflow.chat.game.exception;

/** 403 GAME_FORBIDDEN — caller is not on the GAME_ADMIN_USER_IDS allow-list. */
public class GameForbiddenException extends GameException {

    public GameForbiddenException() {
        super(403, "GAME_FORBIDDEN", "게임 관리자 권한이 없습니다.");
    }
}
