package com.ssafy.keeping.domain.auth.signup.service;

import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.signup.dto.*;
import com.ssafy.keeping.domain.auth.token.AccessTokenService;
import com.ssafy.keeping.domain.auth.token.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SignupFacade {

    private final SignupTxService signupTxService;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenService refreshTokenService;

    public IssuedAuthTokens signupCustomer(CustomerSignupRequest req) {

        SignupResult result = signupTxService.signupCustomerTx(req);

        return issueSuccessToken(result.userId(), result.role(), result.userDto()); // 커밋 이후에 토큰 발급/저장 수행
    }

    public IssuedAuthTokens signupOwner(OwnerSignupRequest req) {

        SignupResult result = signupTxService.signupOwnerTx(req);

        return issueSuccessToken(result.userId(), result.role(), result.userDto()); // 커밋 이후에 토큰 발급/저장 수행
    }

    /**
     * 토큰 발급 + 응답 DTO 생성
     * access + refresh 모두 발급
     */
    private IssuedAuthTokens issueSuccessToken(long userId, UserRole role, RegisterResponse userDto) {
        String accessToken = accessTokenService.issueAccessToken(String.valueOf(userId), role);
        var issuedRefresh = refreshTokenService.issueSingleSession(userId, role);

        SignupResponse body = new SignupResponse(
                userDto,
                AuthResponse.bearer(accessToken, role)
        );

        return new IssuedAuthTokens(body, issuedRefresh.token(), issuedRefresh.ttlSeconds());
    }
}
