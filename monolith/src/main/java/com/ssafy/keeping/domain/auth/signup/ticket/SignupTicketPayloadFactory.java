package com.ssafy.keeping.domain.auth.signup.ticket;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.UserRole;

import java.time.Instant;

public class SignupTicketPayloadFactory {

    public static SignupTicketPayload payload(
            UserRole role,
            AuthProvider providerType,
            String providerId,
            String nickname,
            String profileUrl
    ) {
        return new SignupTicketPayload(
                providerType,
                providerId,
                role,
                nickname,
                profileUrl,
                Instant.now().getEpochSecond()
        );
    }
}
