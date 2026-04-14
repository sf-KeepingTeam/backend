package com.ssafy.keeping.domain.idempotency.model;

import org.springframework.http.HttpStatus;

public class IdempotentResult<T> {
    private final HttpStatus httpStatus;
    private final T body;
    private final boolean replay;
    private final Integer retryAfterSeconds;

    public IdempotentResult(HttpStatus httpStatus, T body, boolean replay, Integer retryAfterSeconds) {
        this.httpStatus = httpStatus;
        this.body = body;
        this.replay = replay;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus getHttpStatus() { return httpStatus; }
    public T getBody() { return body; }
    public boolean isReplay() { return replay; }
    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }

    public static <T> IdempotentResult<T> created(T body) {
        return new IdempotentResult<>(HttpStatus.CREATED, body, false, null); // 최초 처리됨(201)
    }
    public static <T> IdempotentResult<T> ok(T body) {
        return new IdempotentResult<>(HttpStatus.OK, body, false, null);
    }
    public static <T> IdempotentResult<T> okReplay(T body) {
        return new IdempotentResult<>(HttpStatus.OK, body, true, null); // 과거 응답 재생(200)
    }
    public static <T> IdempotentResult<T> accepted() {
        return new IdempotentResult<>(HttpStatus.ACCEPTED, null, false, null); // 처리 중(202), 바디 없음
    }
    public static <T> IdempotentResult<T> acceptedWithRetryAfterSeconds(int seconds) {
        return new IdempotentResult<>(HttpStatus.ACCEPTED, null, false, seconds);
    }
}