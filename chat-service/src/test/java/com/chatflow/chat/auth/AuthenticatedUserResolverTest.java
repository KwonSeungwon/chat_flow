package com.chatflow.chat.auth;

import com.chatflow.chat.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserResolverTest {

    private final AuthenticatedUserResolver resolver = new AuthenticatedUserResolver();

    static class Sample {
        @SuppressWarnings("unused")
        public void required(@AuthenticatedUser String userId) {}
        @SuppressWarnings("unused")
        public void optional(@AuthenticatedUser(required = false) String userId) {}
        @SuppressWarnings("unused")
        public void noAnno(String userId) {}
    }

    private MethodParameter param(String methodName) throws NoSuchMethodException {
        Method m = Sample.class.getMethod(methodName, String.class);
        return new MethodParameter(m, 0);
    }

    @Test
    void supportsParameter_returns_true_only_for_annotated_String() throws Exception {
        assertThat(resolver.supportsParameter(param("required"))).isTrue();
        assertThat(resolver.supportsParameter(param("optional"))).isTrue();
        assertThat(resolver.supportsParameter(param("noAnno"))).isFalse();
    }

    @Test
    void resolves_header_value_when_present() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-42");
        Object value = resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null);
        assertThat(value).isEqualTo("user-42");
    }

    @Test
    void throws_Unauthorized_when_header_missing_and_required() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("인증이 필요합니다.");
    }

    @Test
    void returns_null_when_header_missing_and_not_required() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        Object value = resolver.resolveArgument(param("optional"), null,
                new ServletWebRequest(req), null);
        assertThat(value).isNull();
    }

    @Test
    void throws_Unauthorized_when_header_blank_and_required() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "   ");
        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null))
                .isInstanceOf(UnauthorizedException.class);
    }
}
