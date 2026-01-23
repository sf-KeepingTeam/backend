/**
 * K6 Load Test - Full Integration Test
 *
 * 모든 시나리오를 동시에 실행하는 통합 부하 테스트
 *
 * 실행 방법:
 * k6 run -e BASE_URL=http://localhost:8080 monitoring/load-tests/full-test.js
 *
 * 환경 변수:
 * - BASE_URL: API 서버 URL
 * - TEST_CUSTOMER_ID: 테스트 고객 ID (기본: 9999)
 * - TEST_OWNER_ID: 테스트 점주 ID (기본: 9998)
 * - ENABLE_WRITE_TESTS: 쓰기 테스트 활성화 (true/false)
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';

// Common utilities
import {
  BASE_URL,
  TEST_CUSTOMER_ID,
  TEST_OWNER_ID,
  TEST_STORE_ID,
  customerHeaders,
  ownerHeaders,
  randomSleep,
  randomInt,
  generateIdempotencyKey,
  checkResponse,
  healthCheck,
  verifyCustomerAuth,
  verifyOwnerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomOwnerId,
  VU_CONFIG,
} from './config/common.js';

// Individual scenarios
import { storeCustomerScenario } from './scenarios/store-customer.js';
import { storeOwnerScenario } from './scenarios/store-owner.js';
import { menuCustomerScenario } from './scenarios/menu-customer.js';
import { menuOwnerScenario } from './scenarios/menu-owner.js';
import { menuCategoryScenario } from './scenarios/menu-category.js';
import { walletBalanceScenario, walletTransferScenario } from './scenarios/wallet.js';
import { favoriteScenario } from './scenarios/favorite.js';
import { prepaymentScenario } from './scenarios/prepayment.js';
import { paymentScenario } from './scenarios/payment.js';
import { chargeBonusScenario } from './scenarios/charge-bonus.js';
import { statisticsScenario } from './scenarios/statistics.js';
import { customerProfileScenario } from './scenarios/customer-profile.js';
import { ownerProfileScenario } from './scenarios/owner-profile.js';
import { notificationCustomerScenario, notificationOwnerScenario } from './scenarios/notification.js';
import { authScenario } from './scenarios/auth.js';

export let options = {
  scenarios: {
    // ===== 고객용 조회 API (높은 부하 - 100 VU) =====
    store_customer: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'storeCustomerScenario',
    },
    menu_customer: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'menuCustomerScenario',
    },
    favorite: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'favoriteScenario',
    },
    customer_profile: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'customerProfileScenario',
    },
    notification_customer: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'notificationCustomerScenario',
    },
    auth: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'authScenario',
    },

    // ===== 지갑 API (중점 테스트 - 100/50 VU) =====
    wallet_balance: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'walletBalanceScenario',
    },
    wallet_transfer: {
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

    // ===== 결제 API (중점 테스트 - 50 VU) =====
    prepayment: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'prepaymentScenario',
      startTime: '10s',
    },
    payment: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'paymentScenario',
      startTime: '10s',
    },

    // ===== 점주용 API (중간 부하 - 50 VU) =====
    store_owner: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'storeOwnerScenario',
    },
    menu_owner: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'menuOwnerScenario',
    },
    menu_category: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'menuCategoryScenario',
    },
    charge_bonus: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'chargeBonusScenario',
    },
    statistics: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'statisticsScenario',
    },
    owner_profile: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'ownerProfileScenario',
    },
    notification_owner: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'notificationOwnerScenario',
    },
  },

  thresholds: {
    // 전체 기준
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.02'],

    // 시나리오별 세부 기준
    'http_req_duration{scenario:store_customer}': ['p(95)<300'],
    'http_req_duration{scenario:menu_customer}': ['p(95)<300'],
    'http_req_duration{scenario:wallet_balance}': ['p(95)<300'],
    'http_req_duration{scenario:prepayment}': ['p(95)<2000'],
    'http_req_duration{scenario:payment}': ['p(95)<2000'],
    'http_req_failed{scenario:prepayment}': ['rate<0.001'],
    'http_req_failed{scenario:payment}': ['rate<0.001'],
  },
};

export function setup() {
  logSetupStart('Full Integration Test');

  // 헬스체크
  if (!healthCheck()) {
    throw new Error('Health check failed. Start server with loadtest profile.');
  }

  // 고객/점주 인증 검증
  const customerAuthOk = verifyCustomerAuth();
  const ownerAuthOk = verifyOwnerAuth();

  if (!customerAuthOk || !ownerAuthOk) {
    console.warn('Auth verification failed, but continuing with test...');
  }

  logSetupComplete('Full Integration Test', {
    baseUrl: BASE_URL,
    customerId: TEST_CUSTOMER_ID,
    ownerId: TEST_OWNER_ID,
    customerAuth: customerAuthOk,
    ownerAuth: ownerAuthOk,
  });

  return {
    customerAuth: customerAuthOk,
    ownerAuth: ownerAuthOk,
  };
}

export function teardown(data) {
  console.log('Full Integration Test completed.');
  console.log('Results:', JSON.stringify(data));
}

// Re-export all scenarios for executor access
export {
  storeCustomerScenario,
  storeOwnerScenario,
  menuCustomerScenario,
  menuOwnerScenario,
  menuCategoryScenario,
  walletBalanceScenario,
  walletTransferScenario,
  favoriteScenario,
  prepaymentScenario,
  paymentScenario,
  chargeBonusScenario,
  statisticsScenario,
  customerProfileScenario,
  ownerProfileScenario,
  notificationCustomerScenario,
  notificationOwnerScenario,
  authScenario,
};

// Default function for simple test
export default function () {
  storeCustomerScenario();
  sleep(1);
}
