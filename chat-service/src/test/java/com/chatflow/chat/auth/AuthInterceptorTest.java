package com.chatflow.chat.auth;

import com.chatflow.chat.controller.RoomMembershipGuard;
import com.chatflow.chat.exception.ForbiddenException;
import com.chatflow.chat.exception.UnauthorizedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("authOnly")));
    }

    @Test
    void requireMember_delegates_to_guard() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-1");
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        assertTrue(interceptor.preHandle(req, res, handler("roomGated")));
        verify(membershipGuard).requireMember("room-42", "user-1");
    }

    @Test
    void requireMember_propagates_Forbidden_from_guard() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "user-1");
        req.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE,
                Map.of("roomId", "room-42"));
        MockHttpServletResponse res = new MockHttpServletResponse();
        doThrow(new ForbiddenException("방 멤버가 아닙니다."))
                .when(membershipGuard).requireMember("room-42", "user-1");
        assertThrows(ForbiddenException.class,
                () -> interceptor.preHandle(req, res, handler("roomGated")));
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
