/**
 * K6 Load Test - Store Customer API
 *
 * 고객용 가게 조회 API 테스트: 전체/상세/카테고리별/이름 검색
 * VU: 100 (높은 부하)
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
  VU_CONFIG,
} from '../config/common.js';

export let options = {
  scenarios: {
    store_customer_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 50 },
        { duration: '30s', target: 100 },
        { duration: '20s', target: 100 },
        { duration: '10s', target: 0 },
      ],
      exec: 'storeCustomerScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

// 테스트용 카테고리 목록
const CATEGORIES = ['KOREAN', 'JAPANESE', 'CHINESE', 'WESTERN', 'CAFE'];

// 테스트용 검색어 목록
const SEARCH_NAMES = ['맛집', '카페', '치킨', '피자', '커피'];

export function setup() {
  logSetupStart('Store-Customer');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  // 가게 목록 조회 검증
  const res = http.get(`${BASE_URL}/stores`, { headers: customerHeaders() });
  const passed = check(res, {
    'get all stores works': (r) => r.status === 200,
  });

  if (passed && res.status === 200) {
    try {
      const body = JSON.parse(res.body);
      const storeCount = body.data ? body.data.length : 0;
      logSetupComplete('Store-Customer', { storeCount });
      return { storeCount };
    } catch (e) {
      logSetupComplete('Store-Customer', { error: 'Failed to parse response' });
    }
  }

  return {};
}

export function storeCustomerScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);

  group('Store Customer API - List', () => {
    // 전체 가게 조회
    const allStoresRes = http.get(`${BASE_URL}/stores`, { headers });
    checkResponseWithData(allStoresRes, 'Get all stores');

    sleep(randomSleep(0.1, 0.3));
  });

  group('Store Customer API - Detail', () => {
    // 가게 상세 조회 (테스트 데이터 범위 사용)
    const storeId = getRandomStoreId();
    const storeDetailRes = http.get(`${BASE_URL}/stores/${storeId}`, { headers });

    check(storeDetailRes, {
      'store detail status is 200 or 404': (r) => r.status === 200 || r.status === 404,
    });

    sleep(randomSleep(0.1, 0.3));
  });

  group('Store Customer API - Search', () => {
    // 카테고리별 조회
    const category = CATEGORIES[randomInt(0, CATEGORIES.length - 1)];
    const categoryRes = http.get(`${BASE_URL}/stores?category=${category}`, { headers });

    check(categoryRes, {
      'category search status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.1, 0.2));

    // 이름 검색
    const searchName = SEARCH_NAMES[randomInt(0, SEARCH_NAMES.length - 1)];
    const searchRes = http.get(`${BASE_URL}/stores?name=${encodeURIComponent(searchName)}`, { headers });

    check(searchRes, {
      'name search status is 200': (r) => r.status === 200,
    });
  });

  sleep(randomSleep(0.3, 0.8));
}

export default function () {
  storeCustomerScenario();
}
