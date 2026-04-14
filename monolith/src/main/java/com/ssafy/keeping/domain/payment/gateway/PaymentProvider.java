package com.ssafy.keeping.domain.payment.gateway;

/**
 * 결제 제공자 enum
 * 새로운 결제 제공자 추가 시 여기에 추가
 */
public enum PaymentProvider {
    TOSS("토스페이먼츠"),
    KAKAO("카카오페이"),
    NAVER("네이버페이");

    private final String displayName;

    PaymentProvider(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
