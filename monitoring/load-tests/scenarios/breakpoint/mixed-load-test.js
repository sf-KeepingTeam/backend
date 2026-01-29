/**
 * K6 Mixed Load Test - 복합 부하 테스트
 *
 * 목적: 모놀리식 환경에서 한 서비스의 부하가 다른 서비스에 영향을 주는지 검증
 *
 * 시나리오:
 * 1. Wallet API에 높은 부하 (병목 유발)
 * 2. 동시에 QR API 성능 측정 (핵심 비즈니스)
 *
 * 검증 포인트:
 * - Wallet 부하 시 QR 응답 시간 증가 여부
 * - DB Connection Pool 경합 확인
 * - MSA 분리 필요성 데이터 확보
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import {
  BASE_URL,
  customerHeaders,
  randomSleep,
  randomInt,
  healthCheck,
  verifyCustomerAuth,
  logSetupStart,
  logSetupComplete,
  getRandomCustomerId,
  getRandomStoreId,
  getRandomWalletId,
  getRandomGroupId,
} from '../../config/common.js';

// 커스텀 메트릭: QR vs Wallet 응답시간 비교
const qrDuration = new Trend('qr_api_duration', true);
const walletDuration = new Trend('wallet_api_duration', true);
const qrErrors = new Counter('qr_api_errors');
const walletErrors = new Counter('wallet_api_errors');

export let options = {
  scenarios: {
    // 시나리오 1: Wallet에 높은 부하 (병목 유발)
    wallet_heavy_load: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 300,
      stages: [
        { duration: '30s', target: 50 },   // 워밍업
        { duration: '1m', target: 100 },   // 중간 부하
        { duration: '1m', target: 150 },   // 높은 부하
        { duration: '1m', target: 200 },   // 최대 부하
        { duration: '30s', target: 0 },    // 쿨다운
      ],
      exec: 'walletHeavyLoad',
    },
    // 시나리오 2: QR API 성능 측정 (일정한 부하)
    qr_monitor: {
      executor: 'constant-arrival-rate',
      rate: 30,  // 초당 30 요청 (고정)
      timeUnit: '1s',
      duration: '4m',
      preAllocatedVUs: 50,
      maxVUs: 100,
      exec: 'qrMonitor',
    },
  },
  thresholds: {
    // QR API는 Wallet 부하와 관계없이 빨라야 함
    'qr_api_duration': ['p(95)<500', 'p(99)<1000'],
    // Wallet은 부하 시 느려질 수 있음
    'wallet_api_duration': ['p(95)<2000'],
    // QR 에러율은 낮아야 함
    'qr_api_errors': ['count<100'],
  },
};

export function setup() {
  logSetupStart('Mixed Load Test');

  if (!healthCheck()) {
    throw new Error('Health check failed');
  }

  if (!verifyCustomerAuth()) {
    throw new Error('Customer auth verification failed');
  }

  logSetupComplete('Mixed Load Test');
  return {};
}

// Wallet API 높은 부하 (병목 유발)
export function walletHeavyLoad() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const groupId = getRandomGroupId();
  const storeId = getRandomStoreId();

  group('Wallet Heavy Load', () => {
    // 개인 지갑 잔액 조회
    const start1 = Date.now();
    const individualRes = http.get(`${BASE_URL}/wallets/individual/balance?page=0&size=10`, {
      headers,
    });
    walletDuration.add(Date.now() - start1);

    if (individualRes.status !== 200) {
      walletErrors.add(1);
    }

    sleep(randomSleep(0.05, 0.1));

    // 모임 지갑 잔액 조회
    const start2 = Date.now();
    const groupRes = http.get(`${BASE_URL}/wallets/groups/${groupId}/balance?page=0&size=10`, {
      headers,
    });
    walletDuration.add(Date.now() - start2);

    sleep(randomSleep(0.05, 0.1));

    // 통합 잔액 조회
    const start3 = Date.now();
    const bothRes = http.get(`${BASE_URL}/wallets/both/balance?page=0&size=10`, { headers });
    walletDuration.add(Date.now() - start3);

    sleep(randomSleep(0.05, 0.1));

    // 가게별 상세 조회
    const start4 = Date.now();
    const storeDetailRes = http.get(
      `${BASE_URL}/wallets/individual/stores/${storeId}/detail?page=0&size=10`,
      { headers }
    );
    walletDuration.add(Date.now() - start4);
  });

  sleep(randomSleep(0.1, 0.2));
}

// QR API 성능 모니터링 (핵심 비즈니스)
export function qrMonitor() {
  const customerId = getRandomCustomerId();
  const headers = customerHeaders(customerId);
  const storeId = getRandomStoreId();
  const walletId = getRandomWalletId();

  group('QR Monitor', () => {
    const qrPayload = JSON.stringify({
      walletId: walletId,
      mode: 'CPQR',
      bindStoreId: storeId,
      ttlSeconds: 60,
    });

    const start = Date.now();
    const qrRes = http.post(`${BASE_URL}/cpqr/new`, qrPayload, { headers });
    const duration = Date.now() - start;

    qrDuration.add(duration);

    const success = check(qrRes, {
      'QR create status is 201': (r) => r.status === 201,
    });

    if (!success) {
      qrErrors.add(1);
      // 실패 시 로그
      if (qrRes.status !== 201 && qrRes.status !== 400 && qrRes.status !== 403) {
        console.log(`QR failed: status=${qrRes.status}, duration=${duration}ms`);
      }
    }
  });

  sleep(randomSleep(0.1, 0.2));
}

export function handleSummary(data) {
  // 결과 요약 출력
  const qrP95 = data.metrics.qr_api_duration?.values['p(95)'] || 0;
  const qrP99 = data.metrics.qr_api_duration?.values['p(99)'] || 0;
  const walletP95 = data.metrics.wallet_api_duration?.values['p(95)'] || 0;
  const walletP99 = data.metrics.wallet_api_duration?.values['p(99)'] || 0;

  console.log('\n========== MIXED LOAD TEST SUMMARY ==========');
  console.log(`\nQR API (핵심 비즈니스):`);
  console.log(`  p(95): ${qrP95.toFixed(2)}ms`);
  console.log(`  p(99): ${qrP99.toFixed(2)}ms`);
  console.log(`\nWallet API (부하 유발):`);
  console.log(`  p(95): ${walletP95.toFixed(2)}ms`);
  console.log(`  p(99): ${walletP99.toFixed(2)}ms`);
  console.log(`\n영향도 분석:`);

  if (qrP95 > 500) {
    console.log(`  ⚠️  QR p95(${qrP95.toFixed(0)}ms) > 500ms: Wallet 부하가 QR에 영향!`);
    console.log(`  → MSA 분리 필요성 확인됨`);
  } else {
    console.log(`  ✓ QR p95(${qrP95.toFixed(0)}ms) < 500ms: Wallet 부하가 QR에 영향 적음`);
  }

  console.log('==============================================\n');

  return {};
}

export default function() {
  // 기본 실행 시 두 시나리오 번갈아 실행
  if (Math.random() < 0.7) {
    walletHeavyLoad();
  } else {
    qrMonitor();
  }
}
