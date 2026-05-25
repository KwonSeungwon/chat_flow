package com.chatflow.chat.result;

/**
 * Sealed Result&lt;T, E&gt; — explicit success/failure outcome instead of
 * boolean + side-channel exception. Stored in-repo (no Vavr footprint).
 *
 * Use {@link #ok(Object)} / {@link #ok()} / {@link #err(Object, String)} to
 * construct. Use {@link #isSuccess()} / {@link #isFailure()} to branch,
 * then {@link #value()} / {@link #error()} + {@link #message()} to extract.
 */
public sealed interface Result<T, E> permits Result.Success, Result.Failure {

    boolean isSuccess();

    default boolean isFailure() {
        return !isSuccess();
    }

    /**
     * @return the success value
     * @throws IllegalStateException if this is a Failure
     */
    T value();

    /**
     * @return the error code
     * @throws IllegalStateException if this is a Success
     */
    E error();

    /**
     * @return the failure message (null if this is a Success)
     */
    String message();

    static <T, E> Result<T, E> ok(T value) {
        return new Success<>(value);
    }

    @SuppressWarnings("unchecked")
    static <E> Result<Void, E> ok() {
        return (Result<Void, E>) Success.VOID;
    }

    static <T, E> Result<T, E> err(E error, String message) {
        return new Failure<>(error, message);
    }

    record Success<T, E>(T value) implements Result<T, E> {
        static final Success<Void, ?> VOID = new Success<>(null);

        @Override public boolean isSuccess() { return true; }
        @Override public E error() {
            throw new IllegalStateException("Cannot call error() on Success");
        }
        @Override public String message() { return null; }
    }

    record Failure<T, E>(E error, String message) implements Result<T, E> {
        @Override public boolean isSuccess() { return false; }
        @Override public T value() {
            throw new IllegalStateException("Cannot call value() on Failure: " + error + " — " + message);
        }
    }
}
