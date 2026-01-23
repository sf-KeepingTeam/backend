/**
 * K6 Load Test - Common Configuration
 *
 * 공통 설정 파일: BASE_URL, 헤더 생성 함수, 유틸리티 등
 */

import { check } from 'k6';
import http from 'k6/http';

// Import test data configuration
import {
  CUSTOMER_ID_START,
  CUSTOMER_ID_END,
  CUSTOMER_COUNT,
  OWNER_ID_START,
  OWNER_ID_END,
  OWNER_COUNT,
  STORE_ID_START,
  STORE_ID_END,
  STORE_COUNT,
  WALLET_ID_START,
  WALLET_ID_END,
  CATEGORY_ID_START,
  CATEGORY_ID_END,
  MENU_ID_START,
  MENU_ID_END,
  GROUP_ID_START,
  GROUP_ID_END,
  getRandomCustomerId,
  getRandomOwnerId,
  getRandomStoreId,
  getRandomWalletId,
  getRandomCategoryId,
  getRandomMenuId,
  getRandomGroupId,
  getStoreIdsByOwner,
  getRandomStoreIdByOwner,
  VU_CONFIG,
} from '../data/test-data-config.js';

// Re-export test data functions for convenience
export {
  CUSTOMER_ID_START,
  CUSTOMER_ID_END,
  CUSTOMER_COUNT,
  OWNER_ID_START,
  OWNER_ID_END,
  OWNER_COUNT,
  STORE_ID_START,
  STORE_ID_END,
  STORE_COUNT,
  WALLET_ID_START,
  WALLET_ID_END,
  CATEGORY_ID_START,
  CATEGORY_ID_END,
  MENU_ID_START,
  MENU_ID_END,
  GROUP_ID_START,
  GROUP_ID_END,
  getRandomCustomerId,
  getRandomOwnerId,
  getRandomStoreId,
  getRandomWalletId,
  getRandomCategoryId,
  getRandomMenuId,
  getRandomGroupId,
  getStoreIdsByOwner,
  getRandomStoreIdByOwner,
  VU_CONFIG,
};

// ===== 환경 변수 =====
export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
// 단일 테스트용 ID (기존 호환성 유지, 랜덤 ID 사용 권장)
export const TEST_CUSTOMER_ID = __ENV.TEST_CUSTOMER_ID || String(CUSTOMER_ID_START);
export const TEST_OWNER_ID = __ENV.TEST_OWNER_ID || String(OWNER_ID_START);
export const TEST_STORE_ID = __ENV.TEST_STORE_ID || String(STORE_ID_START);
export const TEST_GROUP_ID = __ENV.TEST_GROUP_ID || '1';

// ===== 쓰기 테스트 플래그 =====
// ENABLE_WRITE_TESTS 대신 ENABLE_CREATE_UPDATE_TESTS 사용 (DELETE 제외)
export const ENABLE_CREATE_UPDATE_TESTS = __ENV.ENABLE_CREATE_UPDATE_TESTS === 'true' || __ENV.ENABLE_WRITE_TESTS === 'true';

// ===== 헤더 생성 함수 =====

/**
 * 고객용 요청 헤더 생성
 * @param {string} customerId - 고객 ID (기본값: TEST_CUSTOMER_ID)
 * @returns {Object} 헤더 객체
 */
export function customerHeaders(customerId = TEST_CUSTOMER_ID) {
  return {
    'Content-Type': 'application/json',
    'X-Test-User-Id': customerId,
    'X-Test-Role': 'CUSTOMER',
  };
}

/**
 * 점주용 요청 헤더 생성
 * @param {string} ownerId - 점주 ID (기본값: TEST_OWNER_ID)
 * @returns {Object} 헤더 객체
 */
export function ownerHeaders(ownerId = TEST_OWNER_ID) {
  return {
    'Content-Type': 'application/json',
    'X-Test-User-Id': ownerId,
    'X-Test-Role': 'OWNER',
  };
}

/**
 * Idempotency-Key가 포함된 고객 헤더 생성
 * @param {string} customerId - 고객 ID
 * @returns {Object} 헤더 객체
 */
export function customerHeadersWithIdempotency(customerId = TEST_CUSTOMER_ID) {
  return {
    ...customerHeaders(customerId),
    'Idempotency-Key': generateIdempotencyKey(),
  };
}

/**
 * Idempotency-Key가 포함된 점주 헤더 생성
 * @param {string} ownerId - 점주 ID
 * @returns {Object} 헤더 객체
 */
export function ownerHeadersWithIdempotency(ownerId = TEST_OWNER_ID) {
  return {
    ...ownerHeaders(ownerId),
    'Idempotency-Key': generateIdempotencyKey(),
  };
}

// ===== 유틸리티 함수 =====

/**
 * UUID v4 생성 (Idempotency-Key용)
 * @returns {string} UUID 문자열
 */
export function generateIdempotencyKey() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function(c) {
    const r = Math.random() * 16 | 0;
    const v = c === 'x' ? r : (r & 0x3 | 0x8);
    return v.toString(16);
  });
}

/**
 * 랜덤 대기 시간 (think time)
 * @param {number} min - 최소 대기 시간 (초)
 * @param {number} max - 최대 대기 시간 (초)
 * @returns {number} 대기 시간 (초)
 */
export function randomSleep(min = 0.5, max = 1.5) {
  return Math.random() * (max - min) + min;
}

/**
 * 랜덤 정수 생성
 * @param {number} min - 최소값
 * @param {number} max - 최대값
 * @returns {number} 랜덤 정수
 */
export function randomInt(min, max) {
  return Math.floor(Math.random() * (max - min + 1)) + min;
}

// ===== 공통 Check 함수 =====

/**
 * 기본 API 응답 검증
 * @param {Object} res - HTTP 응답 객체
 * @param {string} name - 체크 이름
 * @returns {boolean} 검증 결과
 */
export function checkResponse(res, name = 'API') {
  return check(res, {
    [`${name} status is 200`]: (r) => r.status === 200,
    [`${name} response has success`]: (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.success === true;
      } catch {
        return false;
      }
    },
  });
}

/**
 * 생성 API 응답 검증 (201 Created)
 * @param {Object} res - HTTP 응답 객체
 * @param {string} name - 체크 이름
 * @returns {boolean} 검증 결과
 */
export function checkCreatedResponse(res, name = 'API') {
  return check(res, {
    [`${name} status is 201`]: (r) => r.status === 201,
    [`${name} response has success`]: (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.success === true;
      } catch {
        return false;
      }
    },
  });
}

/**
 * 데이터 존재 여부 검증
 * @param {Object} res - HTTP 응답 객체
 * @param {string} name - 체크 이름
 * @returns {boolean} 검증 결과
 */
export function checkResponseWithData(res, name = 'API') {
  return check(res, {
    [`${name} status is 200`]: (r) => r.status === 200,
    [`${name} response has data`]: (r) => {
      try {
        const body = JSON.parse(r.body);
        return body.data !== undefined && body.data !== null;
      } catch {
        return false;
      }
    },
  });
}

// ===== 기본 Thresholds =====
export const defaultThresholds = {
  http_req_duration: ['p(95)<500', 'p(99)<1000'],  // 95%가 500ms, 99%가 1000ms 이내
  http_req_failed: ['rate<0.01'],                   // 실패율 1% 미만
};

// ===== 시나리오별 권장 Thresholds =====
export const scenarioThresholds = {
  // 읽기 전용 API (빠른 응답 기대)
  read: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
  // 쓰기 API (약간 느린 응답 허용)
  write: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.02'],
  },
  // 결제 관련 API (안정성 중시)
  payment: {
    http_req_duration: ['p(95)<2000', 'p(99)<3000'],
    http_req_failed: ['rate<0.001'],  // 0.1% 미만
  },
};

// ===== 헬스체크 =====

/**
 * 서버 헬스체크 및 테스트 백도어 검증
 * @returns {boolean} 검증 결과
 */
export function healthCheck() {
  const res = http.get(`${BASE_URL}/loadtest/health`);
  const passed = check(res, {
    'health check passed': (r) => r.status === 200,
  });

  if (!passed) {
    console.error('LoadTest backdoor is not enabled. Start server with --spring.profiles.active=local,loadtest');
  }

  return passed;
}

/**
 * 고객 인증 검증
 * @param {string} customerId - 고객 ID
 * @returns {boolean} 검증 결과
 */
export function verifyCustomerAuth(customerId = TEST_CUSTOMER_ID) {
  const res = http.get(`${BASE_URL}/loadtest/verify-customer`, {
    headers: customerHeaders(customerId),
  });
  return check(res, {
    'customer auth verified': (r) => r.status === 200,
  });
}

/**
 * 점주 인증 검증
 * @param {string} ownerId - 점주 ID
 * @returns {boolean} 검증 결과
 */
export function verifyOwnerAuth(ownerId = TEST_OWNER_ID) {
  const res = http.get(`${BASE_URL}/loadtest/verify-owner`, {
    headers: ownerHeaders(ownerId),
  });
  return check(res, {
    'owner auth verified': (r) => r.status === 200,
  });
}

// ===== 로깅 유틸리티 =====

/**
 * 테스트 시작 로그
 * @param {string} scenarioName - 시나리오 이름
 */
export function logSetupStart(scenarioName) {
  console.log(`[${scenarioName}] Setup started...`);
}

/**
 * 테스트 완료 로그
 * @param {string} scenarioName - 시나리오 이름
 * @param {Object} data - 추가 데이터
 */
export function logSetupComplete(scenarioName, data = {}) {
  console.log(`[${scenarioName}] Setup completed.`, JSON.stringify(data));
}
