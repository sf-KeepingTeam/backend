package com.ssafy.keeping.domain.notification.dto;

/**
 * 알림 발송용 매장 프로젝션. JPQL new-expression 전용.
 *
 * <p>파생 게터를 추가하지 마라 — Jackson이 프로퍼티로 인식하면 직렬화 사고가 난다 (v2 QrToken.isExpired 사례).
 */
public record StoreNotificationView(String storeName, Long ownerId) {}
