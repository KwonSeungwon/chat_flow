package com.chatflow.chat.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring the caller to be a member of the
 * room identified by {@link #pathVar()} (default "roomId"). The
 * AuthInterceptor first enforces @RequireAuth semantics (401 if no userId),
 * then delegates to RoomMembershipGuard which throws 403 if not a member.
 *
 * The pathVar's value is read from the request URI's path-variable map.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireMember {
    String pathVar() default "roomId";
}
