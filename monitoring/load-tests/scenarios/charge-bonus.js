/**
 * K6 Load Test - Charge Bonus API
 *
 * 충전 보너스 API 테스트: 설정 CRUD
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
  checkCreatedResponse,
  checkResponseWithData,
  healthCheck,
  verifyOwnerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomOwnerId,
  getRandomStoreIdByOwner,
  VU_CONFIG,
  ENABLE_CREATE_UPDATE_TESTS,
} from '../config/common.js';

export let options = {
  scenarios: {
    charge_bonus_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'chargeBonusScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.02'],
  },
};

export function setup() {
  logSetupStart('Charge-Bonus');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyOwnerAuth()) {
    throw new Error('Owner auth verification failed');
  }

  // 충전 보너스 목록 조회 검증
  const res = http.get(`${BASE_URL}/owners/stores/${TEST_STORE_ID}/charge-bonus`, {
    headers: ownerHeaders(),
  });

  const passed = check(res, {
    'get charge bonus list works': (r) => r.status === 200 || r.status === 403 || r.status === 404,
  });

  logSetupComplete('Charge-Bonus', { ownerId: TEST_OWNER_ID });
  return {};
}

export function chargeBonusScenario() {
  // 매 요청마다 랜덤 점주 ID 사용 (캐시 문제 방지)
  const ownerId = getRandomOwnerId();
  const headers = ownerHeaders(ownerId);
  const storeId = getRandomStoreIdByOwner(ownerId);

  group('Charge Bonus API - Read', () => {
    // 충전 보너스 목록 조회
    const listRes = http.get(`${BASE_URL}/owners/stores/${storeId}/charge-bonus`, { headers });

    check(listRes, {
      'list status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });

    sleep(randomSleep(0.2, 0.4));

    // 충전 보너스 상세 조회
    const bonusId = randomInt(1, 5);
    const detailRes = http.get(
      `${BASE_URL}/owners/stores/${storeId}/charge-bonus/${bonusId}`,
      { headers }
    );

    check(detailRes, {
      'detail status is 200 or 403/404': (r) =>
        r.status === 200 || r.status === 403 || r.status === 404,
    });
  });

  // 쓰기 테스트 (활성화 시) - 생성/수정만, 삭제 제외
  if (ENABLE_CREATE_UPDATE_TESTS) {
    group('Charge Bonus API - Write', () => {
      // 충전 보너스 생성
      const createPayload = JSON.stringify({
        chargeAmount: randomInt(10000, 100000),
        bonusAmount: randomInt(500, 5000),
        description: `테스트 보너스 ${Date.now()}`,
      });

      const createRes = http.post(
        `${BASE_URL}/owners/stores/${storeId}/charge-bonus`,
        createPayload,
        { headers }
      );

      check(createRes, {
        'create status is 201 or 4xx': (r) =>
          r.status === 201 || r.status === 400 || r.status === 403 || r.status === 409,
      });

      if (createRes.status === 201) {
        try {
          const body = JSON.parse(createRes.body);
          const bonusId = body.data?.chargeBonusId;

          if (bonusId) {
            sleep(randomSleep(0.2, 0.3));

            // 수정
            const updatePayload = JSON.stringify({
              chargeAmount: randomInt(10000, 100000),
              bonusAmount: randomInt(500, 5000),
              description: `수정된 보너스 ${Date.now()}`,
            });

            const updateRes = http.put(
              `${BASE_URL}/owners/stores/${storeId}/charge-bonus/${bonusId}`,
              updatePayload,
              { headers }
            );

            check(updateRes, {
              'update status is 200 or 4xx': (r) =>
                r.status === 200 || r.status === 400 || r.status === 403,
            });

            // 삭제 테스트 제거 (데이터 일관성 유지)
            // DELETE 테스트는 비활성화됨
          }
        } catch (e) {
          console.log('Failed to parse create response');
        }
      }
    });
  }

  sleep(randomSleep(0.5, 1.0));
}

export default function () {
  chargeBonusScenario();
}
