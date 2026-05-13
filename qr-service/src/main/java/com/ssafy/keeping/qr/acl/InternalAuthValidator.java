package com.ssafy.keeping.qr.acl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InternalAuthValidator {

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    public void validate(String authToken) {
        if (!internalAuthToken.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }
}
