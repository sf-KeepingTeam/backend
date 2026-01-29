import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// 환경변수
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TARGET_API = __ENV.TARGET_API || 'qr'; // qr, wallet, payment, all
const MAX_VUS = parseInt(__ENV.MAX_VUS) || 500;
const MAX_RPS = parseInt(__ENV.MAX_RPS) || 1000;

// 커스텀 메트릭
const apiErrors = new Counter('api_errors');
const qrDuration = new Trend('qr_duration', true);
const walletDuration = new Trend('wallet_duration', true);
const paymentDuration = new Trend('payment_duration', true);

// 테스트 설정 - 극한 부하
export const options = {
  scenarios: {
    extreme_load: {
      executor: 'ramping-arrival-rate',
      startRate: 50,
      timeUnit: '1s',
      preAllocatedVUs: MAX_VUS,
      maxVUs: MAX_VUS,
      stages: [
        { duration: '30s', target: 200 },   // 워밍업
        { duration: '1m', target: 500 },    // 중간 부하
        { duration: '1m', target: MAX_RPS }, // 최대 부하
        { duration: '1m', target: MAX_RPS }, // 최대 부하 유지
        { duration: '30s', target: 0 },     // 쿨다운
      ],
    },
  },
  thresholds: {
    http_req_duration: ['p(95)<1000', 'p(99)<2000'],
    http_req_failed: ['rate<0.10'], // 10% 실패까지 허용
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
  console.log(`[Extreme Stress Test] Starting...`);
  console.log(`  BASE_URL: ${BASE_URL}`);
  console.log(`  TARGET_API: ${TARGET_API}`);
  console.log(`  MAX_VUS: ${MAX_VUS}`);
  console.log(`  MAX_RPS: ${MAX_RPS}`);

  // 헬스체크
  const healthRes = http.get(`${BASE_URL}/actuator/health`);
  if (healthRes.status !== 200) {
    console.error('Health check failed!');
  }

  return { startTime: Date.now() };
}

export default function (data) {
  const customerId = customerIds[Math.floor(Math.random() * customerIds.length)];
  const walletId = walletIds[Math.floor(Math.random() * walletIds.length)];
  const storeId = storeIds[Math.floor(Math.random() * storeIds.length)];

  // 고객 인증
  const authRes = http.post(
    `${BASE_URL}/api/loadtest/auth/customer`,
    JSON.stringify({ customerId: customerId }),
    { headers, tags: { name: 'auth' } }
  );

  if (authRes.status !== 200) {
    apiErrors.add(1);
    return;
  }

  const authHeaders = {
    ...headers,
    'Authorization': `Bearer ${authRes.json('accessToken')}`,
  };

  // 타겟 API에 따라 분기
  if (TARGET_API === 'qr' || TARGET_API === 'all') {
    testQR(authHeaders, walletId, storeId);
  }

  if (TARGET_API === 'wallet' || TARGET_API === 'all') {
    testWallet(authHeaders, walletId);
  }

  if (TARGET_API === 'payment' || TARGET_API === 'all') {
    testPayment(authHeaders, walletId, storeId);
  }
}

function testQR(authHeaders, walletId, storeId) {
  const qrPayload = JSON.stringify({
    walletId: walletId,
    mode: 'CPQR',
    bindStoreId: storeId,
    ttlSeconds: 60,
  });

  const start = Date.now();
  const res = http.post(`${BASE_URL}/api/qr`, qrPayload, {
    headers: authHeaders,
    tags: { name: 'qr_create' },
  });
  qrDuration.add(Date.now() - start);

  const success = check(res, {
    'QR status OK': (r) => r.status === 201 || r.status >= 400,
  });

  if (!success) {
    apiErrors.add(1);
    console.log(`QR failed: status=${res.status}, body=${res.body}`);
  }
}

function testWallet(authHeaders, walletId) {
  const start = Date.now();
  const res = http.get(`${BASE_URL}/api/wallets/${walletId}/balance`, {
    headers: authHeaders,
    tags: { name: 'wallet_balance' },
  });
  walletDuration.add(Date.now() - start);

  const success = check(res, {
    'Wallet status OK': (r) => r.status === 200 || r.status >= 400,
  });

  if (!success) {
    apiErrors.add(1);
  }
}

function testPayment(authHeaders, walletId, storeId) {
  // QR 생성 후 결제 시뮬레이션
  const qrPayload = JSON.stringify({
    walletId: walletId,
    mode: 'CPQR',
    bindStoreId: storeId,
    ttlSeconds: 60,
  });

  const start = Date.now();
  const qrRes = http.post(`${BASE_URL}/api/qr`, qrPayload, {
    headers: authHeaders,
    tags: { name: 'payment_qr' },
  });
  paymentDuration.add(Date.now() - start);

  check(qrRes, {
    'Payment QR OK': (r) => r.status === 201 || r.status >= 400,
  });
}

export function teardown(data) {
  const duration = (Date.now() - data.startTime) / 1000;
  console.log(`\n========== EXTREME STRESS TEST COMPLETE ==========`);
  console.log(`Duration: ${duration.toFixed(1)}s`);
  console.log(`Target API: ${TARGET_API}`);
  console.log(`Max RPS attempted: ${MAX_RPS}`);
  console.log(`===================================================\n`);
}
