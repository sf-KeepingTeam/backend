package com.ssafy.keeping.domain.internal.exception;

/**
 * Internal API 인증 실패 예외
 * 401 Unauthorized 응답을 반환
 */
public class InternalApiAuthException extends RuntimeException {

    public InternalApiAuthException(String message) {
        super(message);
    }

    public InternalApiAuthException(String message, Throwable cause) {
        super(message, cause);
    }
}
