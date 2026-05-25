package com.chatflow.chat.result;

import com.chatflow.common.dto.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponsesTest {

    @Test
    void from_maps_NOT_FOUND_to_404() {
        Result<String, ChatErrorCode> r = Result.err(ChatErrorCode.NOT_FOUND, "no such room");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(resp.getBody().isSuccess()).isFalse();
        assertThat(resp.getBody().getMessage()).isEqualTo("no such room");
    }

    @Test
    void from_maps_FORBIDDEN_to_403() {
        Result<Void, ChatErrorCode> r = Result.err(ChatErrorCode.FORBIDDEN, "not author");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void from_maps_MUTED_to_423() {
        Result<Void, ChatErrorCode> r = Result.err(ChatErrorCode.MUTED, "muted");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
    }

    @Test
    void from_maps_GONE_to_410() {
        Result<Void, ChatErrorCode> r = Result.err(ChatErrorCode.GONE, "expired");
        ResponseEntity<ApiResponse<?>> resp = ErrorResponses.from(r);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.GONE);
    }
}
