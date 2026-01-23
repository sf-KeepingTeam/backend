/**
 * K6 Load Test - Menu Customer API
 *
 * 고객용 메뉴 조회 API 테스트: 전체 메뉴/카테고리별 메뉴 조회
 * VU: 50
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_STORE_ID,
  customerHeaders,
  randomSleep,
  randomInt,
  checkResponse,
  checkResponseWithData,
  healthCheck,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomStoreId,
  getRandomCategoryId,
  VU_CONFIG,
} from '../config/common.js';

export let options = {
  scenarios: {
    menu_customer_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'menuCustomerScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Menu-Customer');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  // 메뉴 조회 검증
  const res = http.get(`${BASE_URL}/stores/${TEST_STORE_ID}/menus`, { headers: customerHeaders() });
  const passed = check(res, {
    'get menus works': (r) => r.status === 200 || r.status === 404,
  });

  if (passed && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      const menuCount = body.data ? body.data.length : 0;
      logSetupComplete('Menu-Customer', { storeId: TEST_STORE_ID, menuCount });
      return { menuCount };
    } catch (e) {
      logSetupComplete('Menu-Customer', { error: 'Failed to parse response' });
    }
  }

  return {};
}

export function menuCustomerScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();

  group('Menu Customer API - All Menus', () => {
    // 가게의 전체 메뉴 조회
    const allMenusRes = http.get(`${BASE_URL}/stores/${storeId}/menus`, { headers });

    check(allMenusRes, {
      'get all menus status is 200 or 404': (r) => r.status === 200 || r.status === 404,
    });

    sleep(randomSleep(0.1, 0.3));
  });

  group('Menu Customer API - By Category', () => {
    // 카테고리별 메뉴 조회 (테스트 데이터 범위 사용)
    const categoryId = getRandomCategoryId();
    const categoryMenusRes = http.get(
      `${BASE_URL}/stores/${storeId}/menus/categories/${categoryId}`,
      { headers }
    );

    check(categoryMenusRes, {
      'get menus by category status is 200 or 404': (r) =>
        r.status === 200 || r.status === 404,
    });
  });

  sleep(randomSleep(0.3, 0.8));
}

export default function () {
  menuCustomerScenario();
}
