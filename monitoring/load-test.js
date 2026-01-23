import http from 'k6/http';
import { check, sleep, group } from 'k6';

// 테스트 설정
export let options = {
  scenarios: {
    // Owner 매장 조회 부하 테스트
    owner_stores_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },  // 10초 동안 20 VU까지 증가
        { duration: '30s', target: 50 },  // 30초 동안 50 VU 유지
        { duration: '10s', target: 0 },   // 10초 동안 0으로 감소
      ],
      exec: 'ownerStoresScenario',
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500'],  // 95%의 요청이 500ms 이내
    http_req_failed: ['rate<0.01'],    // 실패율 1% 미만
  },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 테스트용 유저 ID
const TEST_CUSTOMER_ID = __ENV.TEST_CUSTOMER_ID || '9999';
const TEST_OWNER_ID = __ENV.TEST_OWNER_ID || '9998';

// Owner 요청 헤더
const ownerHeaders = {
  'Content-Type': 'application/json',
  'X-Test-User-Id': TEST_OWNER_ID,
  'X-Test-Role': 'OWNER',
};

// 헬스체크 (테스트 시작 전 검증)
export function setup() {
  const healthRes = http.get(`${BASE_URL}/loadtest/health`);
  check(healthRes, {
    'health check passed': (r) => r.status === 200,
  });

  if (healthRes.status !== 200) {
    throw new Error('LoadTest backdoor is not enabled. Start server with --spring.profiles.active=local,loadtest');
  }

  // Owner 인증 검증
  const verifyRes = http.get(`${BASE_URL}/loadtest/verify-owner`, { headers: ownerHeaders });
  check(verifyRes, {
    'owner auth verified': (r) => r.status === 200,
  });

  // 내 매장 조회 API 사전 검증
  const storesRes = http.get(`${BASE_URL}/owners/stores`, { headers: ownerHeaders });
  check(storesRes, {
    'get my stores works': (r) => r.status === 200,
  });

  if (storesRes.status === 200) {
    const body = JSON.parse(storesRes.body);
    console.log(`Setup completed. Owner ${TEST_OWNER_ID} has ${body.data ? body.data.length : 0} stores.`);
  }
}

// Owner 매장 조회 시나리오
export function ownerStoresScenario() {
  group('Owner Stores API', () => {
    // 내 매장 조회 API
    const res = http.get(`${BASE_URL}/owners/stores`, { headers: ownerHeaders });

    check(res, {
      'status is 200': (r) => r.status === 200,
      'response has success': (r) => {
        const body = JSON.parse(r.body);
        return body.success === true;
      },
      'response has data': (r) => {
        const body = JSON.parse(r.body);
        return Array.isArray(body.data);
      },
    });
  });

  sleep(Math.random() * 1 + 0.5);  // 0.5~1.5초 랜덤 대기
}

// 기본 실행 함수 (단순 테스트용)
export default function () {
  const res = http.get(`${BASE_URL}/owners/stores`, { headers: ownerHeaders });
  check(res, {
    'status is 200': (r) => r.status === 200,
  });
  sleep(1);
}
