/**
 * K6 Load Test - Customer Profile API
 *
 * 고객 프로필 API 테스트: 프로필 조회, 수정, 내 그룹 조회
 * VU: 10
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
  VU_CONFIG,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    customer_profile_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'customerProfileScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Customer-Profile');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  // 프로필 조회 검증
  const res = http.get(`${BASE_URL}/customers/me`, {
    headers: customerHeaders(),
  });

  const passed = check(res, {
    'get my profile works': (r) => r.status === 200,
  });

  logSetupComplete('Customer-Profile', { customerId: TEST_CUSTOMER_ID });
  return {};
}

export function customerProfileScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);

  group('Customer Profile API - Read', () => {
    // 내 프로필 조회
    const profileRes = http.get(`${BASE_URL}/customers/me`, { headers });
    checkResponseWithData(profileRes, 'Get my profile');

    sleep(randomSleep(0.2, 0.4));

    // 내가 속한 그룹 조회
    const groupsRes = http.get(`${BASE_URL}/customers/me/groups`, { headers });
    checkResponseWithData(groupsRes, 'Get my groups');
  });

  // 프로필 수정 테스트 (쓰기 활성화 시)
  if (ENABLE_CREATE_UPDATE_TESTS) {
    group('Customer Profile API - Update', () => {
      const updatePayload = JSON.stringify({
        nickname: `테스트유저_${randomInt(1, 1000)}`,
        // 필요한 다른 필드 추가
      });

      const updateRes = http.put(`${BASE_URL}/customers/me`, updatePayload, { headers });

      check(updateRes, {
        'update profile status is 200 or 4xx': (r) =>
          r.status === 200 || r.status === 400,
      });
    });
  }

  sleep(randomSleep(0.5, 1.0));
}

export default function () {
  customerProfileScenario();
}
