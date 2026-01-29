/**
 * K6 Breakpoint Test - QR API
 *
 * QR 생성 API 한계점 탐색: 결제 시작점으로 중요도 높음
 * 자동 중단: p(95)>500ms 또는 p(99)>1000ms 또는 실패율>5%
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
    qr_breakpoint: {
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
  logSetupStart('QR Breakpoint');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  logSetupComplete('QR Breakpoint');
  return {};
}

export default function() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();

  group('QR API Breakpoint', () => {
    // QR 토큰 생성 (CPQR 모드)
    const walletId = getRandomWalletId();
    const qrPayloadCpqr = JSON.stringify({
      walletId: walletId,
      mode: 'CPQR',
      bindStoreId: storeId,
      ttlSeconds: 60,
    });

    const qrResCpqr = http.post(`${BASE_URL}/cpqr/new`, qrPayloadCpqr, { headers });

    check(qrResCpqr, {
      'QR create (CPQR) status is 201 or 4xx': (r) =>
        r.status === 201 || r.status === 400 || r.status === 403,
    });

    sleep(randomSleep(0.1, 0.2));

    // QR 토큰 생성 (MPQR 모드 - 가게 고정 QR)
    const qrPayloadMpqr = JSON.stringify({
      walletId: walletId,
      mode: 'MPQR',
      bindStoreId: storeId,
      ttlSeconds: 120,
    });

    const qrResMpqr = http.post(`${BASE_URL}/cpqr/new`, qrPayloadMpqr, { headers });

    check(qrResMpqr, {
      'QR create (MPQR) status is 201 or 4xx': (r) =>
        r.status === 201 || r.status === 400 || r.status === 403,
    });
  });

  sleep(randomSleep(0.1, 0.3));
}
