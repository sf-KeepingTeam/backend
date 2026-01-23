/**
 * K6 Load Test - Prepayment API (중점 테스트)
 *
 * 선결제 API 테스트: 예약 → 승인 흐름
 * VU: 10
 *
 * 2단계 흐름:
 * 1. POST /api/v1/stores/{storeId}/prepayment/reserve - 예약
 * 2. POST /api/v1/stores/{storeId}/prepayment/confirm - 승인 (Idempotency)
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
  VU_CONFIG,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    prepayment_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'prepaymentScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<2000', 'p(99)<3000'],
    http_req_failed: ['rate<0.001'],  // 결제는 0.1% 미만 실패율
  },
};

export function setup() {
  logSetupStart('Prepayment');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  logSetupComplete('Prepayment', { customerId: TEST_CUSTOMER_ID });
  return {};
}

export function prepaymentScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();
  const amount = randomInt(10000, 100000);

  group('Prepayment API - Reserve', () => {
    // 1단계: 선결제 예약
    const reservePayload = JSON.stringify({
      amount: amount,
    });

    const reserveRes = http.post(
      `${BASE_URL}/api/v1/stores/${storeId}/prepayment/reserve`,
      reservePayload,
      { headers }
    );

    const reservePassed = check(reserveRes, {
      'reserve status is 201 or 4xx': (r) =>
        r.status === 201 || r.status === 400 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.3, 0.5));

    // 예약 성공 시 승인 단계 진행 (쓰기 테스트 활성화 시)
    if (reservePassed && reserveRes.status === 201) {
      try {
        const reserveBody = JSON.parse(reserveRes.body);
        const orderId = reserveBody.data?.orderId;

        if (orderId && ENABLE_CREATE_UPDATE_TESTS) {
          group('Prepayment API - Confirm', () => {
            // 2단계: 선결제 승인 (토스 결제 완료 후)
            const confirmPayload = JSON.stringify({
              orderId: orderId,
              paymentKey: `test-payment-key-${generateIdempotencyKey()}`,
              amount: amount,
            });

            const confirmRes = http.post(
              `${BASE_URL}/api/v1/stores/${storeId}/prepayment/confirm`,
              confirmPayload,
              { headers }
            );

            check(confirmRes, {
              'confirm status is 200/201 or 4xx': (r) =>
                r.status === 200 || r.status === 201 ||
                r.status === 400 || r.status === 403 || r.status === 409,
            });
          });
        }
      } catch (e) {
        console.log('Failed to parse reserve response');
      }
    }
  });

  sleep(randomSleep(0.5, 1.0));
}

// 단순 예약만 테스트 (승인 없음)
export function prepaymentReserveOnlyScenario() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();

  const reservePayload = JSON.stringify({
    amount: randomInt(10000, 100000),
  });

  const res = http.post(
    `${BASE_URL}/api/v1/stores/${storeId}/prepayment/reserve`,
    reservePayload,
    { headers }
  );

  check(res, {
    'reserve status is 201 or 4xx': (r) =>
      r.status === 201 || r.status === 400 || r.status === 403 || r.status === 404,
  });

  sleep(randomSleep(0.5, 1.0));
}

export default function () {
  prepaymentScenario();
}
