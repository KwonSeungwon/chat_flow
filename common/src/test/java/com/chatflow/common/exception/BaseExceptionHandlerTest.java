package com.chatflow.common.exception;

import com.chatflow.common.dto.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link BaseExceptionHandler}.
 *
 * Since BaseExceptionHandler is abstract, a trivial concrete subclass is
 * defined inside the test to invoke each handler method directly.
 * No Spring context is loaded — plain JUnit 5 + AssertJ.
 */
class BaseExceptionHandlerTest {

    static class TestHandler extends BaseExceptionHandler {
    }

    private TestHandler handler;

    @BeforeEach
    void setUp() {
        handler = new TestHandler();
    }

    // ── handleValidation ─────────────────────────────────────

    @Test
    void handleValidation_returns400WithFieldErrors() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(null, "obj");
        bindingResult.addError(new FieldError("obj", "name", "must not be blank"));
        bindingResult.addError(new FieldError("obj", "email", "must be a valid email"));

        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getCode()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getTimestamp()).isNotNull();
        assertThat(body.getFieldErrors())
                .containsEntry("name", "must not be blank")
                .containsEntry("email", "must be a valid email")
                .hasSize(2);
    }

    @Test
    void handleValidation_singleFieldError() {
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(null, "request");
        bindingResult.addError(new FieldError("request", "age", "must be at least 1"));

        MethodArgumentNotValidException ex =
                new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getFieldErrors())
                .containsEntry("age", "must be at least 1")
                .hasSize(1);
    }

    // ── handleMessageNotReadable ─────────────────────────────

    @Test
    void handleMessageNotReadable_returns400() {
        HttpMessageNotReadableException ex =
                new HttpMessageNotReadableException("Malformed JSON", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<ErrorResponse> response = handler.handleMessageNotReadable(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getCode()).isEqualTo("INVALID_REQUEST_BODY");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getTimestamp()).isNotNull();
    }

    // ── handleIllegalArgument ────────────────────────────────

    @Test
    void handleIllegalArgument_returns400() {
        IllegalArgumentException ex =
                new IllegalArgumentException("Invalid room ID");

        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getCode()).isEqualTo("BAD_REQUEST");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getTimestamp()).isNotNull();
    }

    // ── handleServletRequestBinding ──────────────────────────

    @Test
    void handleServletRequestBinding_returns400() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("q", "String");

        ResponseEntity<ErrorResponse> response = handler.handleServletRequestBinding(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getCode()).isEqualTo("MISSING_REQUEST_PARAMETER");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getTimestamp()).isNotNull();
    }

    // ── handleTypeMismatch ───────────────────────────────────

    @Test
    void handleTypeMismatch_returns400() {
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException(
                        "abc", Integer.class, "page", null,
                        new NumberFormatException("For input string: \"abc\""));

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(400);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(400);
        assertThat(body.getCode()).isEqualTo("TYPE_MISMATCH");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getTimestamp()).isNotNull();
    }

    // ── handleMethodNotSupported ─────────────────────────────

    @Test
    void handleMethodNotSupported_returns405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("POST");

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(405);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(405);
        assertThat(body.getCode()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getTimestamp()).isNotNull();
    }

    // ── handleGeneral ────────────────────────────────────────

    @Test
    void handleGeneral_returns500() {
        Exception ex = new RuntimeException("Something unexpected");

        ResponseEntity<ErrorResponse> response = handler.handleGeneral(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);

        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(500);
        assertThat(body.getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.getMessage()).isNotBlank();
        assertThat(body.getTimestamp()).isNotNull();
    }

    @Test
    void handleGeneral_withNullPointerException_returns500() {
        NullPointerException ex = new NullPointerException("null reference");

        ResponseEntity<ErrorResponse> response = handler.handleGeneral(ex);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
    }
}
