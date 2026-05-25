package com.chatflow.chat.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Controller-method parameter annotation. Resolves to the value of the
 * X-User-Id request header. If {@link #required()} is true (default) and
 * the header is missing/blank, the AuthenticatedUserResolver throws
 * UnauthorizedException. If false, resolves to null — for endpoints that
 * support anonymous callers (e.g. lobby room list).
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuthenticatedUser {
    boolean required() default true;
}
