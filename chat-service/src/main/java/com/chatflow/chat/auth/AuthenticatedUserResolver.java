package com.chatflow.chat.auth;

import com.chatflow.chat.exception.UnauthorizedException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

public class AuthenticatedUserResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthenticatedUser.class)
                && parameter.getParameterType().equals(String.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                   ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest,
                                   WebDataBinderFactory binderFactory) {
        AuthenticatedUser anno = parameter.getParameterAnnotation(AuthenticatedUser.class);
        String value = webRequest.getHeader(AuthHeaders.X_USER_ID);
        boolean blank = value == null || value.isBlank();
        if (blank) {
            if (anno != null && !anno.required()) return null;
            throw new UnauthorizedException("인증이 필요합니다.");
        }
        return value;
    }
}
