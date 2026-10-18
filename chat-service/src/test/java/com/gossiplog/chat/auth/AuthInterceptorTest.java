package com.gossiplog.chat.auth;

import com.gossiplog.chat.controller.RoomMembershipGuard;
import com.gossiplog.chat.exception.ForbiddenException;
import com.gossiplog.chat.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import java.util.List;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;

import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock RoomMembershipGuard membershipGuard;
    private AuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuthInterceptor(membershipGuard);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    }

    @SuppressWarnings("unused")
    static class Sample {
        @RequireAuth
        public void authOnly() {}
        @RequireMember(pathVar = "roomId")
        public void roomGated() {}
        public void noAnno() {}
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Method m = Sample.class.getMethod(methodName);
        return new HandlerMethod(new Sample(), m);
    }

    @Test
    void no_annotation_passes_through() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("noAnno")));
        verifyNoInteractions(membershipGuard);
    }

    @Test
    void requireAuth_throws_when_header_missing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(req, res, handler("authOnly")));
    }

    @Test
    void requireAuth_passes_when_header_present() throws Exception {
        authenticate("user-1");
        MockHttpServletRequest req = new MockHttpServletRequest();
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("authOnly")));
    }

    @Test
    void requireMember_checks_the_principal_not_the_X_User_Id_header() throws Exception {
        authenticate("user-1");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "victim");
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        assertTrue(interceptor.preHandle(req, new MockHttpServletResponse(), handler("roomGated")));
        verify(membershipGuard).requireMember("room-42", "user-1");
    }

    @Test
    void requireAuth_throws_for_a_bare_header_without_authentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-1");
        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(req, new MockHttpServletResponse(), handler("authOnly")));
    }

    @Test
    void requireMember_delegates_to_guard() throws Exception {
        authenticate("user-1");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("roomGated")));
        verify(membershipGuard).requireMember("room-42", "user-1");
    }

    @Test
    void requireMember_propagates_Forbidden_from_guard() throws Exception {
        authenticate("user-1");
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                .when(membershipGuard).requireMember("room-42", "user-1");
        assertThrows(ForbiddenException.class,
                () -> interceptor.preHandle(req, res, handler("roomGated")));
    }

    @Test
    void requireMember_throws_MissingRoomPathVariable_when_pathVar_missing() throws Exception {
        authenticate("user-1");
        MockHttpServletRequest req = new MockHttpServletRequest();
        // Deliberately do NOT set URI_TEMPLATE_VARIABLES_ATTRIBUTE — simulates
        // a @RequireMember annotation on a method whose URI lacks the path var.
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThrows(MissingRoomPathVariableException.class,
                () -> interceptor.preHandle(req, res, handler("roomGated")));
        verifyNoInteractions(membershipGuard);
    }

    @Test
    void requireMember_throws_Unauthorized_when_header_missing() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertThrows(UnauthorizedException.class,
                () -> interceptor.preHandle(req, res, handler("roomGated")));
        verifyNoInteractions(membershipGuard);
    }
}
