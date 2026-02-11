package com.ssafy.keeping.qr.gateway;

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
