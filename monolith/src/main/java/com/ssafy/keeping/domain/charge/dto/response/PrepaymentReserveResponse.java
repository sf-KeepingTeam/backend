package com.ssafy.keeping.domain.charge.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 선결제 예약 응답 DTO
 * 프론트엔드가 토스 결제창에 사용할 정보 반환
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PrepaymentReserveResponse {

    /**
     * 예약 ID
     */
    private Long reservationId;

    /**
     * 주문 ID (토스에 전달할 ID)
     */
    private String orderId;

    /**
     * 충전 금액 (서버에서 확정한 금액)
     */
    private Long amount;

    /**
     * 주문명
     */
    private String orderName;

    /**
     * 만료 시간
     */
    private LocalDateTime expiresAt;

    /**
     * 가게 이름
     */
    private String storeName;
}
