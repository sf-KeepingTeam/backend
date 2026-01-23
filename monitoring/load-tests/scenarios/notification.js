/**
 * K6 Load Test - Notification API
 *
 * 알림 API 테스트: 알림 목록 조회, 읽지 않은 알림, 읽음 처리
 * VU: 10
 *
 * 참고: SSE 구독은 별도 테스트 필요
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_CUSTOMER_ID,
  TEST_OWNER_ID,
  customerHeaders,
  ownerHeaders,
  randomSleep,
  randomInt,
  checkResponse,
  checkResponseWithData,
  healthCheck,
  verifyCustomerAuth,
  verifyOwnerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomOwnerId,
  VU_CONFIG,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    notification_customer_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 50 },
        { duration: '2m', target: 100 },
        { duration: '30s', target: 0 },
      ],
      exec: 'notificationCustomerScenario',
    },
    notification_owner_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'notificationOwnerScenario',
      startTime: '5s',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<300', 'p(99)<500'],
    http_req_failed: ['rate<0.01'],
  },
};

export function setup() {
  logSetupStart('Notification');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  // 고객/점주 인증은 각 시나리오에서 수행

  logSetupComplete('Notification', {
    customerId: TEST_CUSTOMER_ID,
    ownerId: TEST_OWNER_ID,
  });
  return {};
}

// 고객 알림 시나리오
export function notificationCustomerScenario() {
  // 매 요청마다 랜덤 고객 ID 사용 (캐시 문제 방지)
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);

  group('Notification Customer API', () => {
    // 알림 목록 조회
    const listRes = http.get(
      `${BASE_URL}/api/notifications/customer/${customerId}?page=0&size=20`,
      { headers }
    );

    check(listRes, {
      'customer notification list status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.1, 0.2));

    // 읽지 않은 알림 개수 조회
    const countRes = http.get(
      `${BASE_URL}/api/notifications/customer/${customerId}/unread-count`,
      { headers }
    );

    check(countRes, {
      'unread count status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.1, 0.2));

    // 읽지 않은 알림 목록 조회
    const unreadRes = http.get(
      `${BASE_URL}/api/notifications/customer/${customerId}/unread?page=0&size=20`,
      { headers }
    );

    check(unreadRes, {
      'unread list status is 200': (r) => r.status === 200,
    });

    // 읽음 처리 테스트 (쓰기 활성화 시)
    if (ENABLE_CREATE_UPDATE_TESTS) {
      const notificationId = randomInt(1, 100);
      const readRes = http.put(
        `${BASE_URL}/api/notifications/customer/${customerId}/${notificationId}/read`,
        null,
        { headers }
      );

      check(readRes, {
        'mark as read status is 200 or 404': (r) => r.status === 200 || r.status === 404,
      });
    }
  });

  sleep(randomSleep(0.3, 0.6));
}

// 점주 알림 시나리오
export function notificationOwnerScenario() {
  // 매 요청마다 랜덤 점주 ID 사용 (캐시 문제 방지)
  const ownerId = getRandomOwnerId();
  const headers = ownerHeaders(ownerId);

  group('Notification Owner API', () => {
    // 알림 목록 조회
    const listRes = http.get(
      `${BASE_URL}/api/notifications/owner/${ownerId}?page=0&size=20`,
      { headers }
    );

    check(listRes, {
      'owner notification list status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.1, 0.2));

    // 읽지 않은 알림 개수 조회
    const countRes = http.get(
      `${BASE_URL}/api/notifications/owner/${ownerId}/unread-count`,
      { headers }
    );

    check(countRes, {
      'owner unread count status is 200': (r) => r.status === 200,
    });

    sleep(randomSleep(0.1, 0.2));

    // 읽지 않은 알림 목록 조회
    const unreadRes = http.get(
      `${BASE_URL}/api/notifications/owner/${ownerId}/unread?page=0&size=20`,
      { headers }
    );

    check(unreadRes, {
      'owner unread list status is 200': (r) => r.status === 200,
    });

    // 읽음 처리 테스트 (쓰기 활성화 시)
    if (ENABLE_CREATE_UPDATE_TESTS) {
      const notificationId = randomInt(1, 100);
      const readRes = http.put(
        `${BASE_URL}/api/notifications/owner/${ownerId}/${notificationId}/read`,
        null,
        { headers }
      );

      check(readRes, {
        'owner mark as read status is 200 or 404': (r) => r.status === 200 || r.status === 404,
      });
    }
  });

  sleep(randomSleep(0.3, 0.6));
}

export default function () {
  notificationCustomerScenario();
}
