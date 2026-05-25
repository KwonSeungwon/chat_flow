package com.chatflow.chat.auth;

import com.chatflow.chat.controller.RoomMembershipGuard;
import com.chatflow.chat.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Map;

@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    public static final String HEADER_NAME = "X-User-Id";

    private final RoomMembershipGuard membershipGuard;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod hm)) return true;

        RequireMember member = hm.getMethodAnnotation(RequireMember.class);
        RequireAuth auth = hm.getMethodAnnotation(RequireAuth.class);

        if (member == null && auth == null) return true;

        String userId = request.getHeader(HEADER_NAME);
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException("인증이 필요합니다.");
        }

        if (member != null) {
            String roomId = pathVar(request, member.pathVar());
            if (roomId == null) {
                throw new IllegalStateException(
                        "@RequireMember on " + hm.getMethod()
                                + " but no '" + member.pathVar() + "' path variable resolved");
            }
            membershipGuard.requireMember(roomId, userId);
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private String pathVar(HttpServletRequest request, String name) {
        Object raw = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        if (raw instanceof Map<?, ?> map) {
            Object v = ((Map<String, String>) map).get(name);
            return v == null ? null : v.toString();
        }
        return null;
    }
}
