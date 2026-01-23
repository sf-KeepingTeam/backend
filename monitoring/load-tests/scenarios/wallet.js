/**
 * K6 Load Test - Wallet API (중점 테스트)
 *
 * 지갑 API 테스트: 잔액조회(개인/모임/통합), 공유, 회수
 * VU: 30 (잔액조회), 10 (공유/회수)
 *
 * Idempotency-Key 필수 API: 공유, 회수
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_CUSTOMER_ID,
  TEST_STORE_ID,
  TEST_GROUP_ID,
  customerHeaders,
  customerHeadersWithIdempotency,
  generateIdempotencyKey,
  randomSleep,
  randomInt,
  checkResponse,
  checkResponseWithData,
  healthCheck,
  verifyCustomerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomStoreId,
  getRandomWalletId,
  getRandomGroupId,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    // 잔액 조회 시나리오 (높은 부하 - 100 VU)
    wallet_balance_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'walletBalanceScenario',
    },
    // 공유/회수 시나리오 (중간 부하 - 50 VU)
    wallet_transfer_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'walletTransferScenario',
      startTime: '10s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.01'],
    'http_req_duration{scenario:wallet_balance_load}': ['p(95)<300'],
    'http_req_duration{scenario:wallet_transfer_load}': ['p(95)<2000'],
  },
};

export function setup() {
  logSetupStart('Wallet');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  // 개인 지갑 잔액 조회 검증
  const res = http.get(`${BASE_URL}/wallets/individual/balance`, {
    headers: customerHeaders(),
  });

  const passed = check(res, {
    'get individual balance works': (r) => r.status === 200,
  });

  logSetupComplete('Wallet', { customerId: TEST_CUSTOMER_ID });
  return {};
}

// 잔액 조회 시나리오 (읽기 전용)
export function walletBalanceScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);

  group('Wallet Balance API', () => {
    // 개인 지갑 잔액 조회
    const individualRes = http.get(`${BASE_URL}/wallets/individual/balance?page=0&size=10`, {
      headers,
    });
    checkResponse(individualRes, 'Individual balance');

    sleep(randomSleep(0.1, 0.2));

    // 모임 지갑 잔액 조회
    const groupId = getRandomGroupId();
    const groupRes = http.get(`${BASE_URL}/wallets/groups/${groupId}/balance?page=0&size=10`, {
      headers,
    });
    check(groupRes, {
      'group balance status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.1, 0.2));

    // 통합 잔액 조회
    const bothRes = http.get(`${BASE_URL}/wallets/both/balance?page=0&size=10`, { headers });
    checkResponse(bothRes, 'Both balance');

    sleep(randomSleep(0.1, 0.2));

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

  sleep(randomSleep(0.3, 0.6));
}

// 공유/회수 시나리오 (쓰기 - Idempotency-Key 필수)
export function walletTransferScenario() {
  const groupId = randomInt(1, 3);
  const storeId = randomInt(1, 5);

  group('Wallet Transfer API', () => {
    // 모임 지갑 조회
    const groupWalletRes = http.get(`${BASE_URL}/wallets/groups/${groupId}`, {
      headers: customerHeaders(),
    });

    check(groupWalletRes, {
      'group wallet status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.2, 0.4));

    // 회수 가능 포인트 조회
    const walletId = randomInt(1, 10);
    const reclaimableRes = http.get(
      `${BASE_URL}/wallets/${walletId}/stores/${storeId}/points/available`,
      { headers: customerHeaders() }
    );

    check(reclaimableRes, {
      'reclaimable status is 200 or 404': (r) => r.status === 200 || r.status === 404,
    });

    sleep(randomSleep(0.2, 0.4));

    // 포인트 공유 (테스트 환경에서만 실행)
    // 실제 데이터 변경이 발생하므로 주의
    if (ENABLE_CREATE_UPDATE_TESTS) {
      const sharePayload = JSON.stringify({
        amount: randomInt(100, 1000),
      });

      const shareRes = http.post(
        `${BASE_URL}/wallets/groups/${groupId}/stores/${storeId}`,
        sharePayload,
        {
          headers: {
            ...customerHeaders(),
            'Idempotency-Key': generateIdempotencyKey(),
          },
        }
      );

      check(shareRes, {
        'share status is 200/201/202 or 4xx': (r) =>
          r.status === 200 || r.status === 201 || r.status === 202 ||
          r.status === 400 || r.status === 403 || r.status === 404,
      });

      sleep(randomSleep(0.3, 0.5));

      // 포인트 회수
      const reclaimPayload = JSON.stringify({
        amount: randomInt(50, 500),
      });

      const reclaimRes = http.post(
        `${BASE_URL}/wallets/groups/${groupId}/stores/${storeId}/reclaim`,
        reclaimPayload,
        {
          headers: {
            ...customerHeaders(),
            'Idempotency-Key': generateIdempotencyKey(),
          },
        }
      );

      check(reclaimRes, {
        'reclaim status is 200/201/202 or 4xx': (r) =>
          r.status === 200 || r.status === 201 || r.status === 202 ||
          r.status === 400 || r.status === 403 || r.status === 404,
      });
    }
  });

  sleep(randomSleep(0.5, 1.0));
}

export default function () {
  walletBalanceScenario();
}
