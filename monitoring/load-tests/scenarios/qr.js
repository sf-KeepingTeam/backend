import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';

// 환경변수
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

// 커스텀 메트릭
const qrCreateDuration = new Trend('qr_create_duration', true);
const qrErrors = new Counter('qr_errors');

// 테스트 설정
export const options = {
  scenarios: {
    qr_load: {
      executor: 'ramping-vus',
      startVUs: 0,
      stages: [
        { duration: '10s', target: 20 },
        { duration: '30s', target: 50 },
        { duration: '20s', target: 50 },
        { duration: '10s', target: 0 },
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    http_req_failed: ['rate<0.05'],
  },
};

const headers = {
  'Content-Type': 'application/json',
  'X-LoadTest-Auth': 'loadtest-bypass-token',
};

// 테스트 데이터
const customerIds = [];
const walletIds = [];
const storeIds = [];
for (let i = 1; i <= 100; i++) {
  customerIds.push(10000 + i);
  walletIds.push(40000 + i);
  storeIds.push(30000 + i);
}

export function setup() {
  console.log(`[QR Load Test] BASE_URL: ${BASE_URL}`);

  // 헬스체크
  const healthRes = http.get(`${BASE_URL}/actuator/health`);
  if (healthRes.status !== 200) {
    console.warn('Health check failed, but continuing...');
  }

  return { startTime: Date.now() };
}

export default function (data) {
  const customerId = customerIds[Math.floor(Math.random() * customerIds.length)];
  const walletId = walletIds[Math.floor(Math.random() * walletIds.length)];
  const storeId = storeIds[Math.floor(Math.random() * storeIds.length)];

  // 1. 고객 인증 (LoadTest 백도어)
  const authRes = http.post(
    `${BASE_URL}/api/loadtest/auth/customer`,
    JSON.stringify({ customerId: customerId }),
    { headers, tags: { name: 'auth' } }
  );

  if (authRes.status !== 200) {
    qrErrors.add(1);
    if (__VU === 1 && __ITER < 3) {
      console.log(`인증 실패: status=${authRes.status}, body=${authRes.body}`);
    }
    return;
  }

  const accessToken = authRes.json('accessToken');
  const authHeaders = {
    ...headers,
    'Authorization': `Bearer ${accessToken}`,
  };

  // 2. QR 생성 요청
  const payload = JSON.stringify({
    walletId: walletId,
    mode: 'CPQR',
    bindStoreId: storeId,
    ttlSeconds: 60,
  });

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/qr`, payload, {
    headers: authHeaders,
    tags: { name: 'qr_create' },
  });
  qrCreateDuration.add(Date.now() - start);

  const success = check(res, {
    'QR 생성 성공': (r) => r.status === 201 || r.status === 200,
  });

  if (!success) {
    qrErrors.add(1);
    if (__VU === 1 && __ITER < 3) {
      console.log(`QR 생성 실패: status=${res.status}, body=${res.body}`);
    }
  }

  sleep(0.5);
}

export function teardown(data) {
  const duration = (Date.now() - data.startTime) / 1000;
  console.log(`\n========== QR LOAD TEST COMPLETE ==========`);
  console.log(`Duration: ${duration.toFixed(1)}s`);
  console.log(`============================================\n`);
}
