/**
 * ============================================================
 *  공통 설정 파일 (common.js)
 * ============================================================
 *
 *  이 파일은 모든 테스트 스크립트에서 공유하는 설정을 담고 있습니다.
 *  - BASE_URL: Nginx Gateway의 Public IP
 *  - 테스트용 인증 헤더 생성 함수
 *  - 테스트 데이터 범위 설정
 */

// ============================================================
//  서버 주소 설정
// ============================================================
// ⚠️ 배경부하(monolith)와 결제(qr-service)는 서로 다른 서버로 보내야 한다.
//
//    구성 2(나눔)에서 결제를 main 쪽으로 보내면 EC2 간 왕복이 한 번 더 얹혀서
//    결제 지연이 부풀려진다. 반대로 배경부하를 payment 쪽으로 보내도 마찬가지다.
//
//    Nginx는 띄우지 않기로 했으므로 앱 포트를 직접 때린다
//    (근거: docs/v2/infra/03-네트워크와보안.md §1-1)
//
//    구성 1 (합침)   : 한 서버에 둘 다 있다
//      -e MONO_BASE_URL=http://172.31.39.211:8080 -e QR_BASE_URL=http://172.31.39.211:8081
//    구성 2 (나눔)
//      -e MONO_BASE_URL=http://172.31.32.251:8080 -e QR_BASE_URL=http://172.31.42.168:8081
export const MONO_BASE_URL =
    __ENV.MONO_BASE_URL || __ENV.BASE_URL || 'http://localhost:8080';
export const QR_BASE_URL =
    __ENV.QR_BASE_URL || __ENV.BASE_URL || 'http://localhost:8081';

// 하위호환 (옛 스크립트가 BASE_URL 을 참조하는 경우)
export const BASE_URL = MONO_BASE_URL;

// ============================================================
//  테스트 데이터 범위
// ============================================================
// ⚠️ deploy/seed/seed-loadtest.sql 의 규모와 반드시 일치해야 한다.
//    안 맞으면 존재하지 않는 지갑/매장을 때려서 측정이 전부 실패한다.
//    시드 기본값: 고객 1000 / 매장 100 / 매장당 메뉴 5
//
//    지갑 수(=고객 수)는 최대 VU 보다 커야 한다.
//    같은 지갑에 부하가 몰리면 비관락 경합이 병목으로 잡혀 측정이 오염된다.
//    (v1 벌크헤드 실험에서 이미 관측된 현상 — 공유 다운스트림 DB 락 경합)
export const TEST_DATA = {
    CUSTOMER_COUNT: parseInt(__ENV.CUSTOMER_COUNT || '1000'),
    STORE_COUNT: parseInt(__ENV.STORE_COUNT || '100'),
    MENUS_PER_STORE: parseInt(__ENV.MENUS_PER_STORE || '5'),
    PIN: __ENV.TEST_PIN || '123456',   // Argon2id 로 해시되어 저장됨
};

// ============================================================
//  인증 헤더 생성 함수
// ============================================================
/**
 * 테스트용 인증 헤더를 생성합니다.
 *
 * 실제 JWT 대신, LoadTest 전용 헤더 기반 인증을 사용합니다.
 * 서버가 이 헤더를 읽어서 해당 사용자로 인식합니다.
 *
 * @param {number} userId - 사용자 ID (Customer ID 또는 Store ID)
 * @param {string} role - 'CUSTOMER' 또는 'OWNER'
 * @returns {object} HTTP 요청 헤더
 */
export function getTestHeaders(userId, role = 'CUSTOMER') {
    return {
        'Content-Type': 'application/json',
        'X-Test-User-Id': String(userId),
        'X-Test-User-Role': role,   // QR Service용
        'X-Test-Role': role,        // Monolith용
    };
}

// ============================================================
//  유틸리티 함수
// ============================================================

/**
 * 랜덤 Customer ID를 반환합니다 (1 ~ 100).
 */
export function randomCustomerId() {
    return Math.floor(Math.random() * TEST_DATA.CUSTOMER_COUNT) + 1;
}

/**
 * 랜덤 Store ID를 반환합니다 (1 ~ 20).
 */
export function randomStoreId() {
    return Math.floor(Math.random() * TEST_DATA.STORE_COUNT) + 1;
}

/**
 * 주어진 Store ID에 속하는 랜덤 Menu ID를 반환합니다.
 * 각 Store는 5개의 메뉴를 가지며, ID는 (storeId-1)*5 + 1 부터 시작합니다.
 */
export function randomMenuId(storeId) {
    const baseMenuId = (storeId - 1) * TEST_DATA.MENUS_PER_STORE + 1;
    return baseMenuId + Math.floor(Math.random() * TEST_DATA.MENUS_PER_STORE);
}

/**
 * UUID v4를 생성합니다 (멱등성 키 용도).
 */
export function generateUUID() {
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });
}
