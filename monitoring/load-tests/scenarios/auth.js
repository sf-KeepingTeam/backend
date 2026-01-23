/**
 * K6 Load Test - Auth API
 *
 * 인증 관련 API 테스트: 토큰 재발급, 로그아웃
 * VU: 10
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  customerHeaders,
  randomSleep,
  checkResponse,
  healthCheck,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  VU_CONFIG,
} from '../config/common.js';

export let options = {
  scenarios: {
    auth_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'authScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Auth');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  logSetupComplete('Auth');
  return {};
}

export function authScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);

  group('Auth API', () => {
    // 토큰 재발급 테스트 (쿠키 필요 - 테스트 환경에서는 Mock)
    // 실제 환경에서는 refresh 토큰이 쿠키에 있어야 함
    const refreshRes = http.post(
      `${BASE_URL}/auth/refresh`,
      null,
      {
        headers,
        // 테스트용 쿠키 설정 (실제 환경에서는 로그인 후 쿠키 사용)
        cookies: {
          refresh_token: 'test-refresh-token',
        },
      }
    );

    // 401 또는 200 모두 정상 (토큰 없으면 401)
    check(refreshRes, {
      'refresh returns valid status': (r) => r.status === 200 || r.status === 401,
    });

    sleep(randomSleep(0.3, 0.8));

    // 로그아웃 테스트
    const logoutRes = http.post(
      `${BASE_URL}/auth/logout`,
      null,
      { headers }
    );

    check(logoutRes, {
      'logout status is 200': (r) => r.status === 200,
    });
  });

  sleep(randomSleep());
}

export default function () {
  authScenario();
}
