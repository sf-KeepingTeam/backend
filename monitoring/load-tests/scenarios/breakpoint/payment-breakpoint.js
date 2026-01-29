/**
 * K6 Breakpoint Test - Payment API
 *
 * 결제 API 한계점 탐색: 핵심 비즈니스 API로 MSA 분리 대상
 * 자동 중단: p(95)>1000ms 또는 p(99)>2000ms 또는 실패율>5%
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  customerHeaders,
  randomSleep,
  randomInt,
  healthCheck,
  verifyCustomerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomStoreId,
  getRandomWalletId,
} from '../../config/common.js';

export let options = {
  scenarios: {
    payment_breakpoint: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 500,
      stages: [
        { duration: '1m', target: 30 },
        { duration: '1m', target: 60 },
        { duration: '1m', target: 100 },
        { duration: '1m', target: 150 },
        { duration: '1m', target: 200 },
      ],
    },
  },
  thresholds: {
    'http_req_duration': [
      { threshold: 'p(95)<1000', abortOnFail: true, delayAbortEval: '30s' },
      { threshold: 'p(99)<2000', abortOnFail: true, delayAbortEval: '30s' },
    ],
    'http_req_failed': [
      { threshold: 'rate<0.05', abortOnFail: true, delayAbortEval: '30s' },
    ],
  },
};

export function setup() {
  logSetupStart('Payment Breakpoint');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  logSetupComplete('Payment Breakpoint');
  return {};
}

export default function() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();

  group('Payment API Breakpoint', () => {
    // QR 토큰 생성 (결제 시작점)
    const walletId = getRandomWalletId();
    const qrPayload = JSON.stringify({
      walletId: walletId,
      mode: 'CPQR',
      bindStoreId: storeId,
      ttlSeconds: 60,
    });

    const qrRes = http.post(`${BASE_URL}/cpqr/new`, qrPayload, { headers });

    check(qrRes, {
      'QR create status is 201 or 4xx': (r) =>
        r.status === 201 || r.status === 400 || r.status === 403,
    });
  });

  sleep(randomSleep(0.2, 0.5));
}
