package com.ssafy.keeping.qr.saga.dto;

import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 알림 발송을 위한 Saga 페이로드
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPayload {

    private String targetType;   // CUSTOMER, OWNER
    private Long targetId;
    private String notificationType;
    private String content;
    private Long amount;
    private String storeName;

    /**
     * 결제 요청 알림용 페이로드 생성
     */
    public static NotificationPayload forRequest(PaymentIntent intent, Long customerId, String storeName) {
        String content = String.format("%s에서 결제 요청이 도착하였습니다.", storeName);

        return NotificationPayload.builder()
                .targetType("CUSTOMER")
                .targetId(customerId)
                .notificationType("PAYMENT_REQUEST")
                .content(content)
                .amount(intent.getAmount())
                .storeName(storeName)
                .build();
    }

    /**
     * 결제 승인 - 고객 알림용 페이로드 생성
     */
    public static NotificationPayload forApprovalToCustomer(PaymentIntent intent, Long customerId, String storeName) {
        String content = String.format("%s에서 %,d포인트 사용이 완료되었습니다.", storeName, intent.getAmount());

        return NotificationPayload.builder()
                .targetType("CUSTOMER")
                .targetId(customerId)
                .notificationType("POINT_CHARGE")
                .content(content)
                .amount(intent.getAmount())
                .storeName(storeName)
                .build();
    }

    /**
     * 결제 승인 - 점주 알림용 페이로드 생성
     */
    public static NotificationPayload forApprovalToOwner(PaymentIntent intent, Long ownerId) {
        String content = String.format("고객님이 %,d포인트를 결제 승인하였습니다.", intent.getAmount());

        return NotificationPayload.builder()
                .targetType("OWNER")
                .targetId(ownerId)
                .notificationType("PERSONAL_POINT_USE")
                .content(content)
                .amount(intent.getAmount())
                .build();
    }
}
