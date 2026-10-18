package com.gossiplog.chat.auth;

import com.gossiplog.chat.exception.UnauthorizedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatedUserResolverTest {

    private final AuthenticatedUserResolver resolver = new AuthenticatedUserResolver();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

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
    void resolves_the_authenticated_principal() throws Exception {
        authenticate("user-42");
        MockHttpServletRequest req = new MockHttpServletRequest();
        Object value = resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null);
        assertThat(value).isEqualTo("user-42");
    }

    @Test
    void ignores_an_X_User_Id_header_that_differs_from_the_principal() throws Exception {
        authenticate("user-42");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "victim");
        Object value = resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null);
        assertThat(value).isEqualTo("user-42");
    }

    @Test
    void throws_Unauthorized_for_a_bare_header_without_authentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-42");
        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void anonymous_authentication_is_not_a_user() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "k", "anonymousUser", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThat(resolver.resolveArgument(param("optional"), null,
                new ServletWebRequest(req), null)).isNull();
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
    void throws_Unauthorized_when_principal_blank_and_required() throws Exception {
        authenticate("   ");
        MockHttpServletRequest req = new MockHttpServletRequest();
        assertThatThrownBy(() -> resolver.resolveArgument(param("required"), null,
                new ServletWebRequest(req), null))
                .isInstanceOf(UnauthorizedException.class);
    }
}
