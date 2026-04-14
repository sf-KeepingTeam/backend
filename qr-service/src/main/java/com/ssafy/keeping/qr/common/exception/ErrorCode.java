package com.ssafy.keeping.qr.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 결제 요청(Payment Intent) / 검증
    PAYMENT_INIT_ORDER_EMPTY(HttpStatus.BAD_REQUEST, "주문 항목이 비어 있습니다."),
    PAYMENT_INIT_STORE_ID_REQUIRED(HttpStatus.BAD_REQUEST, "storeId는 필수입니다."),
    PAYMENT_INIT_QUANTITY_INVALID(HttpStatus.BAD_REQUEST, "수량은 1 이상이어야 합니다."),
    PAYMENT_INTENT_NOT_FOUND(HttpStatus.NOT_FOUND, "결제 요청 찾을 수 없습니다."),

    // 멱등성(Idempotency)
    IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Idempotency-Key 헤더가 필요합니다."),
    IDEMPOTENCY_BEGIN_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "멱등성 처리 시작에 실패했습니다."),
    IDEMPOTENCY_BODY_CONFLICT(HttpStatus.CONFLICT, "Idempotency-Key 충돌: 요청 내용이 처음과 다릅니다."),
    IDEMPOTENCY_REPLAY_UNAVAILABLE(HttpStatus.CONFLICT, "이미 처리된 요청이나 응답을 복원할 수 없습니다."),
    IDEMPOTENCY_KEY_INVALID(HttpStatus.BAD_REQUEST, "Idempotency-Key 형식이 잘못되었습니다."),

    // QR
    QR_NOT_FOUND(HttpStatus.NOT_FOUND, "QR 토큰을 찾을 수 없거나 사용 불가 상태입니다."),
    QR_EXPIRED(HttpStatus.GONE, "QR 토큰이 만료되었습니다."),
    QR_STORE_MISMATCH(HttpStatus.FORBIDDEN, "바인딩된 매장과 일치하지 않는 요청입니다."),

    // 세션 토큰
    SESSION_NOT_FOUND(HttpStatus.NOT_FOUND, "세션 토큰을 찾을 수 없습니다. QR을 다시 스캔해주세요."),
    SESSION_EXPIRED(HttpStatus.GONE, "세션이 만료되었습니다. QR을 다시 스캔해주세요."),

    // 스냅샷/직렬화
    REQUEST_CANONICALIZE_FAILED(HttpStatus.BAD_REQUEST, "요청 본문 직렬화에 실패했습니다."),
    RESPONSE_SNAPSHOT_PARSE_FAILED(HttpStatus.CONFLICT, "이전에 처리된 응답을 복원할 수 없습니다."),
    JSON_PARSE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "JSON 파싱/역직렬화에 실패했습니다."),

    // 결제 요청(Payment Intent) / 승인 검증 추가
    PAYMENT_INTENT_STATUS_CONFLICT(HttpStatus.CONFLICT, "결제 요청 상태가 승인 가능 상태가 아닙니다."),
    PAYMENT_INTENT_EXPIRED(HttpStatus.GONE, "결제 요청의 승인 가능 시간이 만료되었습니다."),
    PAYMENT_INTENT_OWNER_MISMATCH(HttpStatus.FORBIDDEN, "결제 요청 소유자와 승인 주체가 일치하지 않습니다."),
    PAYMENT_STATUS_CONFLICT(HttpStatus.CONFLICT, "결제 상태 전이가 유효하지 않습니다."),

    // PIN 인증 관련
    PIN_REQUIRED(HttpStatus.BAD_REQUEST, "결제 비밀번호(PIN)는 필수입니다."),
    PIN_INVALID(HttpStatus.UNAUTHORIZED, "결제 비밀번호(PIN)가 올바르지 않습니다."),

    // 자금/한도 관련
    FUNDS_INSUFFICIENT(HttpStatus.PAYMENT_REQUIRED, "잔액이 부족합니다."),
    FUNDS_CHANGED_BY_OTHER_PAYMENT(HttpStatus.CONFLICT, "다른 결제로 잔액이 변경되었습니다."),
    PAYMENT_IN_PROGRESS(HttpStatus.CONFLICT, "다른 결제가 진행 중입니다. 잠시 후 다시 시도해주세요."),
    PAYMENT_POLICY_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "결제 정책에 따라 승인할 수 없습니다."),
    FUNDS_CAPTURE_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "자금 캡처에 실패했습니다."),

    // 매장/메뉴 관련
    STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 매장을 찾을 수 없습니다."),
    MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "해당 메뉴를 찾을 수 없습니다."),
    MENU_CROSS_STORE_CONFLICT(HttpStatus.CONFLICT, "다른 매장의 메뉴가 포함되어 있습니다."),
    MENU_UNAVAILABLE(HttpStatus.CONFLICT, "품절/비활성 메뉴가 포함되어 있습니다."),

    // 고객 관련
    CUSTOMER_NOT_FOUND(HttpStatus.NOT_FOUND, "고객을 찾을 수 없습니다."),

    // 외부 API 관련
    EXTERNAL_API_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "외부 API 통신 중 오류가 발생했습니다."),

    // Circuit Breaker 관련
    CIRCUIT_BREAKER_OPEN(HttpStatus.SERVICE_UNAVAILABLE, "서비스가 일시적으로 이용 불가합니다. 잠시 후 다시 시도해주세요."),
    SERVICE_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "서비스 응답 시간이 초과되었습니다."),
    MONOLITH_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "결제 서비스에 연결할 수 없습니다."),

    // 클라이언트별 Fallback
    WALLET_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "지갑 서비스가 일시적으로 이용 불가합니다."),
    CUSTOMER_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "고객 서비스가 일시적으로 이용 불가합니다."),
    STORE_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "매장 서비스가 일시적으로 이용 불가합니다."),
    MENU_SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "메뉴 서비스가 일시적으로 이용 불가합니다."),

    // global
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "올바르지 않은 요청값입니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 내부 오류가 발생했습니다."),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다.");

    private final HttpStatus httpStatus;
    private final String message;
}
