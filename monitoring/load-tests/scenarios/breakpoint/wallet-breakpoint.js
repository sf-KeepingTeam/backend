/**
 * K6 Breakpoint Test - Wallet API
 *
 * 지갑 API 한계점 탐색: 잔액 조회/차감이 결제와 연동
 * 자동 중단: p(95)>500ms 또는 p(99)>1000ms 또는 실패율>5%
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  customerHeaders,
  randomSleep,
  healthCheck,
  verifyCustomerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomStoreId,
  getRandomGroupId,
} from '../../config/common.js';

export let options = {
  scenarios: {
    wallet_breakpoint: {
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
  logSetupStart('Wallet Breakpoint');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  logSetupComplete('Wallet Breakpoint');
  return {};
}

export default function() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);

  group('Wallet Balance API Breakpoint', () => {
    // 개인 지갑 잔액 조회
    const individualRes = http.get(`${BASE_URL}/wallets/individual/balance?page=0&size=10`, {
      headers,
    });

    check(individualRes, {
      'individual balance status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.05, 0.1));

    // 모임 지갑 잔액 조회
    const groupId = getRandomGroupId();
    const groupRes = http.get(`${BASE_URL}/wallets/groups/${groupId}/balance?page=0&size=10`, {
      headers,
    });

    check(groupRes, {
      'group balance status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.05, 0.1));

    // 통합 잔액 조회
    const bothRes = http.get(`${BASE_URL}/wallets/both/balance?page=0&size=10`, { headers });

    check(bothRes, {
      'both balance status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.05, 0.1));

    // 가게별 상세 정보 조회
    const storeId = getRandomStoreId();
    const storeDetailRes = http.get(
      `${BASE_URL}/wallets/individual/stores/${storeId}/detail?page=0&size=10`,
      { headers }
    );

    check(storeDetailRes, {
      'store detail status is 200 or 404': (r) => r.status === 200 || r.status === 404,
    });
  });

  sleep(randomSleep(0.1, 0.2));
}
