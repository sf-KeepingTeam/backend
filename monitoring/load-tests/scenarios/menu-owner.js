/**
 * K6 Load Test - Menu Owner API
 *
 * 점주용 메뉴 관리 API 테스트: 메뉴 조회
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
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    menu_owner_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'menuOwnerScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Menu-Owner');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyOwnerAuth()) {
    throw new Error('Owner auth verification failed');
  }

  // 내 가게 메뉴 조회 검증
  const res = http.get(`${BASE_URL}/owners/stores/${TEST_STORE_ID}/menus`, {
    headers: ownerHeaders(),
  });

  const passed = check(res, {
    'get owner menus works': (r) => r.status === 200 || r.status === 403 || r.status === 404,
  });

  if (passed && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      const menuCount = body.data ? body.data.length : 0;
      logSetupComplete('Menu-Owner', { ownerId: TEST_OWNER_ID, menuCount });
      return { menuCount };
    } catch (e) {
      logSetupComplete('Menu-Owner', { error: 'Failed to parse response' });
    }
  }

  return {};
}

export function menuOwnerScenario() {
  // 매 요청마다 랜덤 점주 ID 사용 (캐시 문제 방지)
  const ownerId = getRandomOwnerId();
  const headers = ownerHeaders(ownerId);
  const storeId = getRandomStoreIdByOwner(ownerId);

  group('Menu Owner API - Get Menus', () => {
    // 점주의 가게 메뉴 조회
    const menusRes = http.get(`${BASE_URL}/owners/stores/${storeId}/menus`, { headers });

    check(menusRes, {
      'get owner menus status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });
  });

  sleep(randomSleep(0.5, 1.0));
}

// 주의: 아래 함수들은 데이터 변경이 발생하므로 별도 시나리오로 분리

/**
 * 메뉴 생성 시나리오 (위험 - 데이터 생성)
 * Multipart form data 필요
 */
export function menuCreateScenario(storeId) {
  console.log('Menu creation requires multipart form data - skipping');
}

/**
 * 메뉴 삭제 시나리오 (위험 - 데이터 삭제)
 */
export function menuDeleteScenario(storeId, menuId) {
  const headers = ownerHeaders();

  const res = http.del(`${BASE_URL}/owners/stores/${storeId}/menus/${menuId}`, null, { headers });
  check(res, {
    'menu delete status is 200 or 403/404': (r) =>
      r.status === 200 || r.status === 403 || r.status === 404,
  });
}

export default function () {
  menuOwnerScenario();
}
