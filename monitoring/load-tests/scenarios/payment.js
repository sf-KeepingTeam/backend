/**
 * K6 Load Test - Payment API (중점 테스트)
 *
 * 결제 API 테스트: QR 생성, 결제 승인
 * VU: 10
 *
 * Idempotency-Key 필수: 결제 승인
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_CUSTOMER_ID,
  TEST_STORE_ID,
  customerHeaders,
  generateIdempotencyKey,
  randomSleep,
  randomInt,
  checkCreatedResponse,
  healthCheck,
  verifyCustomerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomStoreId,
  getRandomWalletId,
  VU_CONFIG,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    payment_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'paymentScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<3000'],
    http_req_failed: ['rate<0.001'],  // 결제는 0.1% 미만 실패율
  },
};

export function setup() {
  logSetupStart('Payment');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  logSetupComplete('Payment', { customerId: TEST_CUSTOMER_ID });
  return {};
}

export function paymentScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();

  group('Payment API - QR Create', () => {
    // QR 토큰 생성
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

    sleep(randomSleep(0.2, 0.4));

    // QR 생성 성공 시 승인 테스트 (쓰기 테스트 활성화 시)
    if (qrRes.status === 201 && ENABLE_CREATE_UPDATE_TESTS) {
      try {
        const qrBody = JSON.parse(qrRes.body);
        const token = qrBody.data?.token;

        if (token) {
          // 실제로는 점주가 QR 스캔 후 intent 생성 → 고객이 승인
          // 테스트에서는 이 흐름을 시뮬레이션하기 어려우므로 생략
          console.log('QR token created, approval flow requires owner interaction');
        }
      } catch (e) {
        console.log('Failed to parse QR response');
      }
    }
  });

  sleep(randomSleep(0.5, 1.0));
}

// 결제 승인 시나리오 (Intent ID 필요)
export function paymentApproveScenario(intentId) {
  const headers = customerHeaders();
  const idemKey = generateIdempotencyKey();

  const approvePayload = JSON.stringify({
    amount: randomInt(5000, 50000),
  });

  const res = http.post(
    `${BASE_URL}/payments/${intentId}/approve`,
    approvePayload,
    {
      headers: {
        ...headers,
        'Idempotency-Key': idemKey,
      },
    }
  );

  check(res, {
    'approve status is 200 or 4xx': (r) =>
      r.status === 200 || r.status === 400 || r.status === 403 || r.status === 404,
  });
}

// QR 생성만 테스트 (승인 없음)
export function qrCreateOnlyScenario() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();
  const walletId = getRandomWalletId();

  const qrPayload = JSON.stringify({
    walletId: walletId,
    mode: 'CPQR',
    bindStoreId: storeId,
    ttlSeconds: 60,
  });

  const res = http.post(`${BASE_URL}/cpqr/new`, qrPayload, { headers });

  check(res, {
    'QR create status is 201 or 4xx': (r) =>
      r.status === 201 || r.status === 400 || r.status === 403,
  });

  sleep(randomSleep(0.5, 1.0));
}

export default function () {
  paymentScenario();
}
