/**
 * K6 Load Test - Statistics API
 *
 * 통계 API 테스트: 전체/일별/월별/기간별 통계
 * VU: 5 (낮은 부하)
 *
 * 점주 전용 API
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_OWNER_ID,
  TEST_STORE_ID,
  ownerHeaders,
  randomSleep,
  randomInt,
  checkResponse,
  checkResponseWithData,
  healthCheck,
  verifyOwnerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomOwnerId,
  getRandomStoreIdByOwner,
  VU_CONFIG,
} from '../config/common.js';

export let options = {
  scenarios: {
    statistics_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'statisticsScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],  // 통계는 처리 시간이 길 수 있음
    http_req_failed: ['rate<0.02'],
  },
};

// 테스트용 날짜 생성 함수
function getRandomDate(daysAgo = 30) {
  const date = new Date();
  date.setDate(date.getDate() - randomInt(1, daysAgo));
  return date.toISOString().split('T')[0];  // YYYY-MM-DD 형식
}

function getDateRange(daysAgo = 30) {
  const endDate = new Date();
  const startDate = new Date();
  startDate.setDate(startDate.getDate() - daysAgo);

  return {
    startDate: startDate.toISOString().split('T')[0],
    endDate: endDate.toISOString().split('T')[0],
  };
}

export function setup() {
  logSetupStart('Statistics');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyOwnerAuth()) {
    throw new Error('Owner auth verification failed');
  }

  // 전체 통계 조회 검증
  const res = http.get(`${BASE_URL}/stores/${TEST_STORE_ID}/statistics/overall`, {
    headers: ownerHeaders(),
  });

  const passed = check(res, {
    'get overall statistics works': (r) => r.status === 200 || r.status === 403 || r.status === 404,
  });

  logSetupComplete('Statistics', { ownerId: TEST_OWNER_ID });
  return {};
}

export function statisticsScenario() {
  // 매 요청마다 랜덤 점주 ID 사용 (캐시 문제 방지)
  const ownerId = getRandomOwnerId();
  const headers = ownerHeaders(ownerId);
  const storeId = getRandomStoreIdByOwner(ownerId);

  group('Statistics API - Overall', () => {
    // 전체 누적 통계 조회 (GET)
    const overallRes = http.get(`${BASE_URL}/stores/${storeId}/statistics/overall`, { headers });

    check(overallRes, {
      'overall status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.2, 0.4));
  });

  group('Statistics API - Daily', () => {
    // 일별 통계 조회 (POST)
    const dailyPayload = JSON.stringify({
      date: getRandomDate(30),
    });

    const dailyRes = http.post(
      `${BASE_URL}/stores/${storeId}/statistics/daily`,
      dailyPayload,
      { headers }
    );

    check(dailyRes, {
      'daily status is 200 or 4xx': (r) =>
        r.status === 200 || r.status === 400 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.2, 0.4));
  });

  group('Statistics API - Monthly', () => {
    // 월별 통계 조회 (POST)
    const monthlyPayload = JSON.stringify({
      date: getRandomDate(90),  // 최근 3개월 내
    });

    const monthlyRes = http.post(
      `${BASE_URL}/stores/${storeId}/statistics/monthly`,
      monthlyPayload,
      { headers }
    );

    check(monthlyRes, {
      'monthly status is 200 or 4xx': (r) =>
        r.status === 200 || r.status === 400 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.2, 0.4));
  });

  group('Statistics API - Period', () => {
    // 기간별 통계 조회 (POST)
    const range = getDateRange(randomInt(7, 30));
    const periodPayload = JSON.stringify({
      startDate: range.startDate,
      endDate: range.endDate,
    });

    const periodRes = http.post(
      `${BASE_URL}/stores/${storeId}/statistics/period`,
      periodPayload,
      { headers }
    );

    check(periodRes, {
      'period status is 200 or 4xx': (r) =>
        r.status === 200 || r.status === 400 || r.status === 403 || r.status === 404,
    });
  });

  sleep(randomSleep(0.5, 1.0));
}

export default function () {
  statisticsScenario();
}
