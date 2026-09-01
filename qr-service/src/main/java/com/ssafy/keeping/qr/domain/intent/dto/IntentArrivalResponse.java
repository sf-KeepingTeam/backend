package com.ssafy.keeping.qr.domain.intent.dto;

import java.util.List;

/**
 * GET /api/qr/{tokenId}/intent 롱폴링 성공 응답 (200 OK).
 *
 * <p>손님 화면이 이 응답을 받으면 결제 승인 UI로 전환한다.
 *
 * @param intentPublicId 결제 의도 공개 식별자 (UUID 문자열)
 * @param amount         결제 금액
 * @param storeName      매장명 (캐시 또는 monolith 조회)
 * @param items          주문 항목 목록
 */
public record IntentArrivalResponse(
        String intentPublicId,
        Long amount,
        String storeName,
        List<PaymentIntentItemView> items) {}
