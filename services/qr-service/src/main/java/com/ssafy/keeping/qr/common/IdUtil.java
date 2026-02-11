package com.ssafy.keeping.qr.common;

import java.util.UUID;

public class IdUtil {
    private IdUtil() {}

    public static UUID newId() {
        return UUID.randomUUID();
    }

    public static UUID newUuidV7() {
        return com.github.f4b6a3.uuid.UuidCreator.getTimeOrdered();
    }
}
