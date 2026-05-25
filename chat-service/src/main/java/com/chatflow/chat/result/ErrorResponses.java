package com.chatflow.chat.result;

import com.chatflow.common.dto.ApiResponse;
import org.springframework.http.ResponseEntity;

/**
 * Converts a failed Result<..., ChatErrorCode> into the project's
 * ApiResponse error envelope at the appropriate HTTP status. Success
 * responses are the caller's responsibility -- Result.value() is the
 * payload they wrap into ApiResponse.ok(...).
 */
public final class ErrorResponses {

    private ErrorResponses() {}

    public static ResponseEntity<ApiResponse<?>> from(Result<?, ChatErrorCode> result) {
        if (result.isSuccess()) {
            throw new IllegalArgumentException("ErrorResponses.from called on Success");
        }
        return ResponseEntity.status(result.error().defaultHttpStatus())
                .body(ApiResponse.error(result.message()));
    }
}
