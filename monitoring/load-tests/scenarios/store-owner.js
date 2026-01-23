/**
 * K6 Load Test - Store Owner API
 *
 * 점주용 가게 관리 API 테스트: 내 매장 조회, 상세 조회
 * VU: 10 (낮은 부하)
 *
 * 주의: POST/PATCH/DELETE는 데이터 변경이 발생하므로 주의해서 사용
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_OWNER_ID,
  TEST_STORE_ID,
  ownerHeaders,
  randomSleep,
  randomInt,
  checkResponse,
  checkResponseWithData,
  healthCheck,
  verifyOwnerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomOwnerId,
  getRandomStoreIdByOwner,
  VU_CONFIG,
} from '../config/common.js';

export let options = {
  scenarios: {
    store_owner_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'storeOwnerScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Store-Owner');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyOwnerAuth()) {
    throw new Error('Owner auth verification failed');
  }

  // 내 매장 조회 검증
  const res = http.get(`${BASE_URL}/owners/stores`, { headers: ownerHeaders() });
  const passed = check(res, {
    'get my stores works': (r) => r.status === 200,
  });

  if (passed && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      const storeCount = body.data ? body.data.length : 0;
      logSetupComplete('Store-Owner', { ownerId: TEST_OWNER_ID, storeCount });
      return { storeCount };
    } catch (e) {
      logSetupComplete('Store-Owner', { error: 'Failed to parse response' });
    }
  }

  return {};
}

export function storeOwnerScenario() {
  // 매 요청마다 랜덤 점주 ID 사용 (캐시 문제 방지)
  const ownerId = getRandomOwnerId();
  const headers = ownerHeaders(ownerId);
  const storeId = getRandomStoreIdByOwner(ownerId);

  group('Store Owner API - My Stores', () => {
    // 내 매장 목록 조회
    const myStoresRes = http.get(`${BASE_URL}/owners/stores`, { headers });
    checkResponseWithData(myStoresRes, 'Get my stores');

    sleep(randomSleep(0.2, 0.5));
  });

  group('Store Owner API - Store Detail', () => {
    // 매장 상세 조회 (점주 소유 매장 사용)
    const storeDetailRes = http.get(`${BASE_URL}/owners/stores/${storeId}`, { headers });

    check(storeDetailRes, {
      'store detail status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });
  });

  sleep(randomSleep(0.5, 1.0));
}

// 주의: 아래 함수들은 데이터 변경이 발생하므로 별도 시나리오로 분리

/**
 * 가게 생성 시나리오 (위험 - 데이터 생성)
 * 사용 시 주의 필요
 */
export function storeCreateScenario() {
  const headers = ownerHeaders();

  // Multipart form data는 k6에서 별도 처리 필요
  // 테스트 시에는 JSON으로 대체하거나 생략
  console.log('Store creation requires multipart form data - skipping');
}

/**
 * 가게 삭제 시나리오 (위험 - 데이터 삭제)
 * 사용 시 주의 필요
 */
export function storeDeleteScenario(storeId) {
  const headers = ownerHeaders();

  const res = http.del(`${BASE_URL}/owners/stores/${storeId}`, null, { headers });
  check(res, {
    'store delete status is 200 or 403/404': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
  });
}

export default function () {
  storeOwnerScenario();
}
