package com.ssafy.keeping.domain.auth.cookie;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RefreshCookieManager {

    private final RefreshCookieProperties props;

    public ResponseCookie issue(String refreshToken, long ttlSeconds) {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(props.getName(), refreshToken)
                .httpOnly(true)
                .secure(props.isSecure())
                .sameSite(props.getSameSite())
                .path(props.getPath())
                .maxAge(ttlSeconds);

        if (props.getDomain() != null && !props.getDomain().isBlank()) {
            b.domain(props.getDomain());
        }
        return b.build();
    }

    public ResponseCookie clear() {
        ResponseCookie.ResponseCookieBuilder b = ResponseCookie.from(props.getName(), "")
                .httpOnly(true)
                .secure(props.isSecure())
                .sameSite(props.getSameSite())
                .path(props.getPath())
                .maxAge(0);

        if (props.getDomain() != null && !props.getDomain().isBlank()) {
            b.domain(props.getDomain());
        }
        return b.build();
    }

    public String cookieName() {
        return props.getName();
    }
}
