package com.ssafy.keeping.domain.internal.service;

import com.ssafy.keeping.domain.internal.exception.InternalApiAuthException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InternalAuthValidator {

    @Value("${internal.auth-token:internal-service-token-12345}")
    private String internalAuthToken;

    public void validate(String authToken) {
        if (!internalAuthToken.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new InternalApiAuthException("Internal API 인증 실패");
        }
    }
}
