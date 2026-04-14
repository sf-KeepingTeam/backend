package com.ssafy.keeping.domain.auth.signup.ticket;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.UserRole;

public record SignupTicketPayload(
        AuthProvider providerType,
        String providerId,
        UserRole role,
        String nickname,
        String profileUrl,
        long issuedAtEpochSec // 티켓이 발급된 시각
) {
}
