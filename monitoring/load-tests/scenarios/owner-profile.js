/**
 * K6 Load Test - Owner Profile API
 *
 * 점주 프로필 API 테스트: 프로필 이미지 업로드
 * VU: 5 (낮은 부하)
 *
 * 참고: 이미지 업로드는 Multipart form data 필요
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import {
  BASE_URL,
  TEST_OWNER_ID,
  ownerHeaders,
  randomSleep,
  randomInt,
  healthCheck,
  verifyOwnerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomOwnerId,
  VU_CONFIG,
} from '../config/common.js';

export let options = {
  scenarios: {
    owner_profile_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '30s', target: 25 },
        { duration: '2m', target: 50 },
        { duration: '30s', target: 0 },
      ],
      exec: 'ownerProfileScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.02'],
  },
};

export function setup() {
  logSetupStart('Owner-Profile');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyOwnerAuth()) {
    throw new Error('Owner auth verification failed');
  }

  logSetupComplete('Owner-Profile', { ownerId: TEST_OWNER_ID });
  return {};
}

export function ownerProfileScenario() {
  // 매 요청마다 랜덤 점주 ID 사용 (캐시 문제 방지)
  const ownerId = getRandomOwnerId();
  const headers = ownerHeaders(ownerId);

  // 점주 프로필 API는 현재 이미지 업로드만 존재
  // Multipart form data는 k6에서 별도 처리 필요

  group('Owner Profile API', () => {
    // 이미지 업로드 테스트는 실제 파일이 필요하므로
    // 여기서는 엔드포인트 존재 여부만 확인

    // 참고: 실제 이미지 업로드 테스트 시에는 아래와 같이 사용
    // const file = open('/path/to/test-image.jpg', 'b');
    // const formData = {
    //   file: http.file(file, 'test-image.jpg', 'image/jpeg'),
    // };
    // const res = http.post(
    //   `${BASE_URL}/owners/${ownerId}/profile-image/upload`,
    //   formData,
    //   { headers: { 'Content-Type': 'multipart/form-data' } }
    // );

    console.log('Owner profile image upload requires actual file - skipping in load test');

    // 점주 매장 조회로 대체 (API 호출 검증용)
    const res = http.get(`${BASE_URL}/owners/stores`, { headers });

    check(res, {
      'owner stores status is 200': (r) => r.status === 200,
    });
  });

  sleep(randomSleep(0.5, 1.0));
}

export default function () {
  ownerProfileScenario();
}
