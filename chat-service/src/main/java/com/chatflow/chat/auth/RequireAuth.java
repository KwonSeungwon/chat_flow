package com.chatflow.chat.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring an authenticated caller — the
 * AuthInterceptor short-circuits with 401 if the X-User-Id header is
 * missing or blank.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireAuth {
}
