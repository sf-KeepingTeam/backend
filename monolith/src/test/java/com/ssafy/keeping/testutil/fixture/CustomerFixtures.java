package com.ssafy.keeping.testutil.fixture;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.user.customer.model.Customer;

import java.time.LocalDate;
import java.util.UUID;

public final class CustomerFixtures {

    private CustomerFixtures() {
        // new 방지
    }

    /** 기본 Customer (유니크 값 자동 생성) */
    public static Customer customer() {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        return Customer.builder()
                .providerId("customer-provider-" + uniq)
                .providerType(AuthProvider.KAKAO)   // 너 enum에 맞게 (KAKAO/NAVER/GOOGLE 등)
                .email("customer-" + uniq + "@test.com")
                .phoneNumber("010-1234-" + uniq.substring(0, 4))
                .birth(LocalDate.of(1995, 1, 1))
                .name("테스트고객")
                .gender(Gender.MALE)                // 너 enum에 맞게 (MALE/FEMALE 등)
                .imgUrl("https://example.com/customer.png")
                .build();
    }

    /** 원하는 값 일부 커스텀 */
    public static Customer customer(String name, String email) {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        return Customer.builder()
                .providerId("customer-provider-" + uniq)
                .providerType(AuthProvider.KAKAO)
                .email(email)
                .phoneNumber("010-5678-" + uniq.substring(0, 4))
                .birth(LocalDate.of(1995, 1, 1))
                .name(name)
                .gender(Gender.MALE)
                .imgUrl("https://example.com/customer.png")
                .build();
    }

    /** providerType까지 지정하고 싶을 때 */
    public static Customer customer(AuthProvider providerType, String providerId, String email) {
        String uniq = UUID.randomUUID().toString().substring(0, 8);
        return Customer.builder()
                .providerId(providerId != null ? providerId : "customer-provider-" + uniq)
                .providerType(providerType != null ? providerType : AuthProvider.KAKAO)
                .email(email != null ? email : "customer-" + uniq + "@test.com")
                .phoneNumber("010-9999-" + uniq.substring(0, 4))
                .birth(LocalDate.of(1995, 1, 1))
                .name("테스트고객")
                .gender(Gender.MALE)
                .imgUrl("https://example.com/customer.png")
                .build();
    }
}
