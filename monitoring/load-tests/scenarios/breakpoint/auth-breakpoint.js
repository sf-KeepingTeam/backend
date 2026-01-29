/**
 * K6 Breakpoint Test - Auth API
 *
 * 인증 API 한계점 탐색: RPS를 점진적으로 올려 p95/p99가 깨지는 지점 확인
 * 자동 중단: p(95)>500ms 또는 p(99)>1000ms 또는 실패율>5%
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  customerHeaders,
  randomSleep,
  healthCheck,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
} from '../../config/common.js';

export let options = {
  scenarios: {
    auth_breakpoint: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { duration: '1m', target: 50 },
        { duration: '1m', target: 100 },
        { duration: '1m', target: 200 },
        { duration: '1m', target: 300 },
      ],
    },
  },
  thresholds: {
    'http_req_duration': [
      { threshold: 'p(95)<500', abortOnFail: true, delayAbortEval: '30s' },
      { threshold: 'p(99)<1000', abortOnFail: true, delayAbortEval: '30s' },
    ],
    'http_req_failed': [
      { threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' },
    ],
  },
};

export function setup() {
  logSetupStart('Auth Breakpoint');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  logSetupComplete('Auth Breakpoint');
  return {};
}

export default function() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);

  group('Auth API Breakpoint', () => {
    // 토큰 재발급 테스트
    const refreshRes = http.post(
      `${BASE_URL}/auth/refresh`,
      null,
      {
        headers,
        cookies: {
          refresh_token: 'test-refresh-token',
        },
      }
    );

    check(refreshRes, {
      'refresh returns valid status': (r) => r.status === 200 || r.status === 401,
    });

    sleep(randomSleep(0.1, 0.3));

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

  sleep(randomSleep(0.1, 0.3));
}
