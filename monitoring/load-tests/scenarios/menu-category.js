/**
 * K6 Load Test - Menu Category API
 *
 * 메뉴 카테고리 API 테스트: 고객/점주 카테고리 조회 및 관리
 * VU: 5 (낮은 부하)
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_OWNER_ID,
  TEST_STORE_ID,
  customerHeaders,
  ownerHeaders,
  randomSleep,
  randomInt,
  checkResponse,
  checkResponseWithData,
  healthCheck,
  verifyOwnerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomOwnerId,
  getRandomStoreId,
  getRandomStoreIdByOwner,
  VU_CONFIG,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    menu_category_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'menuCategoryScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Menu-Category');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  // 카테고리 조회 검증
  const res = http.get(`${BASE_URL}/stores/${TEST_STORE_ID}/menus/categories`, {
    headers: customerHeaders(),
  });

  const passed = check(res, {
    'get categories works': (r) => r.status === 200 || r.status === 404,
  });

  if (passed && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      const categoryCount = body.data ? body.data.length : 0;
      logSetupComplete('Menu-Category', { storeId: TEST_STORE_ID, categoryCount });
      return { categoryCount };
    } catch (e) {
      logSetupComplete('Menu-Category', { error: 'Failed to parse response' });
    }
  }

  return {};
}

export function menuCategoryScenario() {
  // 랜덤 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const ownerId = getRandomOwnerId();
  const storeId = getRandomStoreId();
  const ownerStoreId = getRandomStoreIdByOwner(ownerId);

  group('Menu Category API - Customer', () => {
    // 고객용 카테고리 조회
    const customerRes = http.get(`${BASE_URL}/stores/${storeId}/menus/categories`, {
      headers: customerHeaders(customerId),
    });

    check(customerRes, {
      'customer get categories status is 200 or 404': (r) =>
        r.status === 200 || r.status === 404,
    });

    sleep(randomSleep(0.2, 0.4));
  });

  group('Menu Category API - Owner', () => {
    // 점주용 카테고리 조회 (점주 소유 매장 사용)
    const ownerRes = http.get(`${BASE_URL}/owners/stores/${ownerStoreId}/menus/categories`, {
      headers: ownerHeaders(ownerId),
    });

    check(ownerRes, {
      'owner get categories status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });
  });

  sleep(randomSleep(0.5, 1.0));
}

// 점주용 카테고리 생성 시나리오 (데이터 변경)
export function categoryCreateScenario(storeId) {
  const headers = ownerHeaders();

  const payload = JSON.stringify({
    name: `테스트 카테고리 ${randomInt(1, 1000)}`,
    parentId: null,
  });

  const res = http.post(`${BASE_URL}/owners/stores/${storeId}/menus/categories`, payload, {
    headers,
  });

  check(res, {
    'category create status is 201 or 403/409': (r) =>
      r.status === 201 || r.status === 403 || r.status === 409,
  });
}

// 점주용 카테고리 삭제 시나리오 (데이터 변경)
export function categoryDeleteScenario(storeId, categoryId) {
  const headers = ownerHeaders();

  const res = http.del(`${BASE_URL}/owners/stores/${storeId}/menus/categories/${categoryId}`, null, {
    headers,
  });

  check(res, {
    'category delete status is 200 or 403/404': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
  });
}

export default function () {
  menuCategoryScenario();
}
