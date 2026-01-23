/**
 * K6 Load Test - Smoke Test
 *
 * 모든 API 엔드포인트의 빠른 검증 (낮은 VU, 짧은 시간)
 * 새로운 배포 전 기본 동작 확인용
 *
 * 실행 방법:
 * k6 run -e BASE_URL=http://localhost:8080 monitoring/load-tests/smoke-test.js
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_CUSTOMER_ID,
  TEST_OWNER_ID,
  TEST_STORE_ID,
  TEST_GROUP_ID,
  customerHeaders,
  ownerHeaders,
  randomSleep,
  randomInt,
  generateIdempotencyKey,
  healthCheck,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomOwnerId,
  getRandomStoreId,
  getRandomGroupId,
} from './config/common.js';

export let options = {
  vus: 1,  // 단일 VU
  duration: '30s',  // 30초 실행
  thresholds: {
    http_req_duration: ['p(95)<2000'],  // 95%가 2초 이내
    http_req_failed: ['rate<0.1'],       // 10% 미만 실패 허용 (스모크 테스트)
  },
};

export function setup() {
  logSetupStart('Smoke Test');

  if (!healthCheck()) {
    throw new Error('Health check failed. Start server with loadtest profile.');
  }

  logSetupComplete('Smoke Test', {
    baseUrl: BASE_URL,
    customerId: TEST_CUSTOMER_ID,
    ownerId: TEST_OWNER_ID,
  });

  return {};
}

export default function () {
  // 스모크 테스트에서도 랜덤 ID 사용
  const customerId = getRandomCustomerId();
  const ownerId = getRandomOwnerId();
  const storeId = getRandomStoreId();
  const groupId = getRandomGroupId();

  const customerHdrs = customerHeaders(customerId);
  const ownerHdrs = ownerHeaders(ownerId);

  // ===== Auth API =====
  group('Smoke: Auth', () => {
    const res = http.post(`${BASE_URL}/auth/logout`, null, { headers: customerHdrs });
    check(res, { 'auth/logout': (r) => r.status === 200 });
  });
  sleep(0.5);

  // ===== Store Customer API =====
  group('Smoke: Store Customer', () => {
    const res1 = http.get(`${BASE_URL}/stores`, { headers: customerHdrs });
    check(res1, { 'GET /stores': (r) => r.status === 200 });

    const res2 = http.get(`${BASE_URL}/stores/${storeId}`, { headers: customerHdrs });
    check(res2, { 'GET /stores/{id}': (r) => r.status === 200 || r.status === 404 });

    const res3 = http.get(`${BASE_URL}/stores?category=KOREAN`, { headers: customerHdrs });
    check(res3, { 'GET /stores?category': (r) => r.status === 200 });
  });
  sleep(0.5);

  // ===== Store Owner API =====
  group('Smoke: Store Owner', () => {
    const res = http.get(`${BASE_URL}/owners/stores`, { headers: ownerHdrs });
    check(res, { 'GET /owners/stores': (r) => r.status === 200 });
  });
  sleep(0.5);

  // ===== Menu Customer API =====
  group('Smoke: Menu Customer', () => {
    const res1 = http.get(`${BASE_URL}/stores/${storeId}/menus`, { headers: customerHdrs });
    check(res1, { 'GET /stores/{id}/menus': (r) => r.status === 200 || r.status === 404 });

    const res2 = http.get(`${BASE_URL}/stores/${storeId}/menus/categories`, { headers: customerHdrs });
    check(res2, { 'GET /stores/{id}/menus/categories': (r) => r.status === 200 || r.status === 404 });
  });
  sleep(0.5);

  // ===== Menu Owner API =====
  group('Smoke: Menu Owner', () => {
    const res = http.get(`${BASE_URL}/owners/stores/${storeId}/menus`, { headers: ownerHdrs });
    check(res, { 'GET /owners/stores/{id}/menus': (r) => r.status === 200 || r.status === 403 || r.status === 404 });
  });
  sleep(0.5);

  // ===== Wallet API =====
  group('Smoke: Wallet', () => {
    const res1 = http.get(`${BASE_URL}/wallets/individual/balance`, { headers: customerHdrs });
    check(res1, { 'GET /wallets/individual/balance': (r) => r.status === 200 });

    const res2 = http.get(`${BASE_URL}/wallets/groups/${groupId}/balance`, { headers: customerHdrs });
    check(res2, { 'GET /wallets/groups/{id}/balance': (r) => r.status === 200 || r.status === 403 || r.status === 404 });

    const res3 = http.get(`${BASE_URL}/wallets/both/balance`, { headers: customerHdrs });
    check(res3, { 'GET /wallets/both/balance': (r) => r.status === 200 });
  });
  sleep(0.5);

  // ===== Favorite API =====
  group('Smoke: Favorite', () => {
    const res1 = http.get(`${BASE_URL}/favorites`, { headers: customerHdrs });
    check(res1, { 'GET /favorites': (r) => r.status === 200 });

    const res2 = http.get(`${BASE_URL}/favorites/stores/${storeId}/check`, { headers: customerHdrs });
    check(res2, { 'GET /favorites/stores/{id}/check': (r) => r.status === 200 });
  });
  sleep(0.5);

  // ===== Prepayment API =====
  group('Smoke: Prepayment', () => {
    const payload = JSON.stringify({ amount: 10000 });
    const res = http.post(`${BASE_URL}/api/v1/stores/${storeId}/prepayment/reserve`, payload, { headers: customerHdrs });
    check(res, { 'POST /prepayment/reserve': (r) => r.status === 201 || r.status === 400 || r.status === 403 || r.status === 404 });
  });
  sleep(0.5);

  // ===== Payment API =====
  group('Smoke: Payment', () => {
    const payload = JSON.stringify({
      storeId: storeId,
      amount: 10000,
      walletType: 'INDIVIDUAL',
    });
    const res = http.post(`${BASE_URL}/cpqr/new`, payload, { headers: customerHdrs });
    check(res, { 'POST /cpqr/new': (r) => r.status === 201 || r.status === 400 || r.status === 403 });
  });
  sleep(0.5);

  // ===== Charge Bonus API =====
  group('Smoke: Charge Bonus', () => {
    const res = http.get(`${BASE_URL}/owners/stores/${storeId}/charge-bonus`, { headers: ownerHdrs });
    check(res, { 'GET /charge-bonus': (r) => r.status === 200 || r.status === 403 || r.status === 404 });
  });
  sleep(0.5);

  // ===== Statistics API =====
  group('Smoke: Statistics', () => {
    const res = http.get(`${BASE_URL}/stores/${storeId}/statistics/overall`, { headers: ownerHdrs });
    check(res, { 'GET /statistics/overall': (r) => r.status === 200 || r.status === 403 || r.status === 404 });
  });
  sleep(0.5);

  // ===== Customer Profile API =====
  group('Smoke: Customer Profile', () => {
    const res1 = http.get(`${BASE_URL}/customers/me`, { headers: customerHdrs });
    check(res1, { 'GET /customers/me': (r) => r.status === 200 });

    const res2 = http.get(`${BASE_URL}/customers/me/groups`, { headers: customerHdrs });
    check(res2, { 'GET /customers/me/groups': (r) => r.status === 200 });
  });
  sleep(0.5);

  // ===== Notification API =====
  group('Smoke: Notification', () => {
    const res1 = http.get(`${BASE_URL}/api/notifications/customer/${customerId}`, { headers: customerHdrs });
    check(res1, { 'GET /notifications/customer/{id}': (r) => r.status === 200 });

    const res2 = http.get(`${BASE_URL}/api/notifications/customer/${customerId}/unread-count`, { headers: customerHdrs });
    check(res2, { 'GET /notifications/customer/{id}/unread-count': (r) => r.status === 200 });

    const res3 = http.get(`${BASE_URL}/api/notifications/owner/${ownerId}`, { headers: ownerHdrs });
    check(res3, { 'GET /notifications/owner/{id}': (r) => r.status === 200 });
  });
  sleep(0.5);

  // 완료 로그
  console.log('Smoke test iteration completed');
}

export function teardown() {
  console.log('=== Smoke Test Completed ===');
  console.log('All API endpoints verified.');
}
