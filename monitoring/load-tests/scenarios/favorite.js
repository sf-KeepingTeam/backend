/**
 * K6 Load Test - Favorite API
 *
 * 찜 기능 API 테스트: 찜 목록 조회, 토글, 상태 확인
 * VU: 30
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_CUSTOMER_ID,
  customerHeaders,
  randomSleep,
  randomInt,
  checkResponse,
  checkResponseWithData,
  healthCheck,
  verifyCustomerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomStoreId,
  VU_CONFIG,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    favorite_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'favoriteScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Favorite');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  // 찜 목록 조회 검증
  const res = http.get(`${BASE_URL}/favorites?page=0&size=10`, {
    headers: customerHeaders(),
  });

  const passed = check(res, {
    'get favorites works': (r) => r.status === 200,
  });

  if (passed && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      logSetupComplete('Favorite', { customerId: TEST_CUSTOMER_ID });
    } catch (e) {
      logSetupComplete('Favorite', { error: 'Failed to parse response' });
    }
  }

  return {};
}

export function favoriteScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();

  group('Favorite API - Read', () => {
    // 찜 목록 조회
    const listRes = http.get(`${BASE_URL}/favorites?page=0&size=10`, { headers });
    checkResponseWithData(listRes, 'Get favorites');

    sleep(randomSleep(0.1, 0.3));

    // 찜 상태 확인
    const checkRes = http.get(`${BASE_URL}/favorites/stores/${storeId}/check`, { headers });

    check(checkRes, {
      'check favorite status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.1, 0.3));
  });

  // 찜 토글 (쓰기 테스트 활성화 시 - DELETE 제외, 토글만 허용)
  if (ENABLE_CREATE_UPDATE_TESTS) {
    group('Favorite API - Toggle', () => {
      const toggleRes = http.post(`${BASE_URL}/favorites/stores/${storeId}`, null, { headers });

      check(toggleRes, {
        'toggle favorite status is 200': (r) => r.status === 200,
      });
    });
  }

  sleep(randomSleep(0.3, 0.6));
}

export default function () {
  favoriteScenario();
}
