package com.ssafy.keeping.testutil.fixture;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.user.owner.model.Owner;

import java.time.LocalDate;
import java.util.UUID;

public final class OwnerFixtures {

    private OwnerFixtures() {}

    public static Owner owner() {
        return Owner.builder()
                .providerId("kakao_" + UUID.randomUUID())
                .providerType(AuthProvider.KAKAO)
                .name("테스트점주")
                .email("owner@test.com")
                .phoneNumber("010-0000-0000")
                .birth(LocalDate.of(1990, 1, 1))
                .gender(Gender.MALE)
                .imgUrl("https://example.com/owner.png")
                .build();
    }

    public static Owner owner(AuthProvider providerType, String providerId, String name) {
        return Owner.builder()
                .providerId(providerId)
                .providerType(providerType)
                .name(name)
                .email(name.toLowerCase() + "@test.com")
                .imgUrl("https://example.com/owner.png")
                .build();
    }
}
