package com.ssafy.keeping.qr.domain.intent.dto;

import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import lombok.*;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentIntentDetailResponse {

    private String intentId;
    private Long storeId;
    private Long customerId;
    private Long amount;
    private PaymentStatus status;
    private String createdAt;
    private String expiresAt;
    private String approvedAt;
    private String declinedAt;
    private String canceledAt;
    private String completedAt;
    private List<PaymentIntentItemView> items;

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter ISO_OFFSET = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private static String toIsoKst(LocalDateTime t) {
        return (t == null) ? null : t.atZone(KST).toOffsetDateTime().format(ISO_OFFSET);
    }

    /** 엔티티 + 아이템뷰 목록 → 상세 응답으로 변환 (KST ISO) */
    public static PaymentIntentDetailResponse from(PaymentIntent e, List<PaymentIntentItemView> items) {
        return PaymentIntentDetailResponse.builder()
                .intentId(e.getPublicId().toString())
                .storeId(e.getStoreId())
                .customerId(e.getCustomerId())
                .amount(e.getAmount())
                .status(e.getStatus())
                .createdAt(toIsoKst(e.getCreatedAt()))
                .expiresAt(toIsoKst(e.getExpiresAt()))
                .approvedAt(toIsoKst(e.getApprovedAt()))
                .declinedAt(toIsoKst(e.getDeclinedAt()))
                .canceledAt(toIsoKst(e.getCanceledAt()))
                .completedAt(toIsoKst(e.getCompletedAt()))
                .items(items)
                .build();
    }
}
