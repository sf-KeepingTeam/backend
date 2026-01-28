package com.ssafy.keeping.domain.auth.signup.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * CustomerCreated 이벤트 페이로드
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerCreatedPayload {
    private Long customerId;
    private String name;
    private String phone;
    private String fcmToken;
}
