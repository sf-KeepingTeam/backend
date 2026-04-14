package com.ssafy.keeping.domain.payment.common;

import java.util.UUID;

public class IdUtil {
    private IdUtil() {}

    public static UUID newId() {
        // 기본은 랜덤 UUID (v4)
        return UUID.randomUUID();
    }

    // 라이브러리 쓰면 v7도 가능 (예: com.github.f4b6a3:uuid-creator)
    public static UUID newUuidV7() {
        return com.github.f4b6a3.uuid.UuidCreator.getTimeOrdered();
    }
}