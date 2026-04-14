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
// 실행 시 환경변수로 지정: k6 run -e BASE_URL=http://43.203.181.93 ...
// 지정하지 않으면 기본값 사용
export const BASE_URL = __ENV.BASE_URL || 'http://43.203.181.93';

// ============================================================
//  테스트 데이터 범위
// ============================================================
// deploy/loadtest/LOADTEST_RESULTS.md 기준 테스트 데이터
export const TEST_DATA = {
    CUSTOMER_COUNT: 100,    // Customer ID: 1 ~ 100
    STORE_COUNT: 20,        // Store ID: 1 ~ 20
    MENUS_PER_STORE: 5,     // Store당 메뉴 5개 (Main)
    PIN: '123456',          // 모든 Customer 동일 PIN
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
