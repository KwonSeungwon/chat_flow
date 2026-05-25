package com.chatflow.chat.result;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResultTest {

    enum E { FOO, BAR }

    @Test
    void ok_holds_value_and_isSuccess() {
        Result<String, E> r = Result.ok("hello");
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.isFailure()).isFalse();
        assertThat(r.value()).isEqualTo("hello");
    }

    @Test
    void err_holds_code_and_message() {
        Result<String, E> r = Result.err(E.FOO, "bad");
        assertThat(r.isSuccess()).isFalse();
        assertThat(r.isFailure()).isTrue();
        assertThat(r.error()).isEqualTo(E.FOO);
        assertThat(r.message()).isEqualTo("bad");
    }

    @Test
    void value_on_failure_throws() {
        Result<String, E> r = Result.err(E.FOO, "bad");
        assertThatThrownBy(r::value).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void error_on_success_throws() {
        Result<String, E> r = Result.ok("hello");
        assertThatThrownBy(r::error).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ok_void_factory() {
        Result<Void, E> r = Result.ok();
        assertThat(r.isSuccess()).isTrue();
        assertThat(r.value()).isNull();
    }
}
