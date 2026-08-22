/**
 * ============================================================
 *  02-qr-payment-flow.js — QR 결제 성능 측정 스크립트
 * ============================================================
 *
 *  [목적]
 *  QR 결제의 전체 플로우를 실행하면서 각 단계별 응답시간을 측정합니다.
 *  01-background-load.js와 동시에 실행하여
 *  "다른 기능이 바쁜 상황에서 QR 결제가 얼마나 걸리는가"를 측정합니다.
 *
 *  [QR 결제 플로우 — 3단계]
 *
 *  1단계: QR 생성 (소비자)
 *     소비자가 앱에서 "결제" 버튼을 누르면 QR 토큰이 생성됩니다.
 *     → POST /api/qr
 *     → Redis에 QR 토큰 저장
 *
 *  2단계: 결제 요청 - Intent (점주)
 *     점주가 QR을 스캔하고 메뉴/수량을 입력합니다.
 *     → POST /api/qr/cpqr/{tokenId}/initiate
 *     → Store/Menu 정보 조회 필요 (캐시 or 모놀리스 호출)
 *
 *  3단계: 결제 승인 - Approve (소비자)
 *     소비자가 결제 내역을 확인하고 PIN 번호를 입력합니다.
 *     → POST /api/qr/payments/{intentId}/approve
 *     → PIN 검증 + 잔액 차감 (반드시 모놀리스 동기 호출 — 캐싱 불가)
 *
 *  [측정 메트릭]
 *  - qr_create_time: 1단계 소요시간
 *  - intent_time:    2단계 소요시간 (캐싱 효과가 가장 크게 나타나는 단계)
 *  - approve_time:   3단계 소요시간 (캐싱 불가, 모놀리스 동기 호출 필수)
 *
 *  [실행 방법]
 *  k6 run -e BASE_URL=http://<NGINX_IP> 02-qr-payment-flow.js
 *
 *  VU 수와 시간을 변경하려면:
 *  k6 run -e BASE_URL=http://<NGINX_IP> -e QR_VUS=100 -e QR_DURATION=5m 02-qr-payment-flow.js
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import {
    BASE_URL,
    TEST_DATA,
    getTestHeaders,
    generateUUID,
} from './common.js';

// ============================================================
//  커스텀 메트릭 — 각 단계의 응답시간을 개별 측정
// ============================================================
const qrCreateTime = new Trend('qr_create_time', true);   // 1단계: QR 생성
const scanTime = new Trend('scan_time', true);             // 2단계: 점주 스캔
const intentTime = new Trend('intent_time', true);         // 3단계: 결제 요청
const approveTime = new Trend('approve_time', true);       // 4단계: 결제 승인
const totalFlowTime = new Trend('total_flow_time', true);  // 전체 플로우 소요시간

const paymentSuccess = new Rate('payment_success_rate');    // 결제 성공률
const paymentFailures = new Counter('payment_failures');    // 결제 실패 수

// ============================================================
//  부하 설정
// ============================================================
const QR_MAX_VUS = parseInt(__ENV.QR_VUS || '50');
const QR_DURATION = __ENV.QR_DURATION || '3m';

// 램프업 시간을 전체의 1/3로 설정
const rampDuration = Math.max(30, Math.floor(parseDuration(QR_DURATION) / 3));

export const options = {
    scenarios: {
        qr_payment: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                // Warm-up: 0에서 목표 VU까지 점진적 증가
                { duration: `${rampDuration}s`, target: Math.floor(QR_MAX_VUS / 2) },
                // Ramp-up: 목표 VU까지 증가
                { duration: `${rampDuration}s`, target: QR_MAX_VUS },
                // Steady: 목표 VU 유지 (핵심 측정 구간)
                { duration: `${rampDuration}s`, target: QR_MAX_VUS },
                // Ramp-down: 점진적 감소
                { duration: '30s', target: 0 },
            ],
        },
    },
    thresholds: {
        'qr_create_time': ['p(95)<500'],     // QR 생성: 500ms 이내
        'intent_time': ['p(95)<500'],         // 결제 요청: 500ms 이내
        'approve_time': ['p(95)<1000'],       // 결제 승인: 1초 이내
        'http_req_failed': ['rate<0.05'],     // 전체 에러율: 5% 이내
    },
};

// ============================================================
//  메인 함수 — 각 VU가 반복 실행합니다
// ============================================================
export default function () {
    // ──────────────────────────────────────────────
    //  테스트 데이터 준비
    //  - 각 VU마다 다른 Customer/Store를 사용하여 실제 환경을 시뮬레이션
    //  - __VU: 현재 VU 번호, __ITER: 현재 반복 횟수
    // ──────────────────────────────────────────────
    const customerId = (__VU % TEST_DATA.CUSTOMER_COUNT) + 1;
    const walletId = customerId;  // Wallet ID = Customer ID (1:1 매핑)
    const storeId = ((__VU + __ITER) % TEST_DATA.STORE_COUNT) + 1;
    const menuId = (storeId - 1) * TEST_DATA.MENUS_PER_STORE + ((__ITER % TEST_DATA.MENUS_PER_STORE) + 1);

    // 소비자 헤더와 점주 헤더를 각각 생성
    const customerHeaders = getTestHeaders(customerId, 'CUSTOMER');
    const ownerHeaders = getTestHeaders(storeId, 'OWNER');

    const flowStart = Date.now();

    group('QR 결제 전체 플로우', function () {

        // ══════════════════════════════════════════
        //  1단계: QR 토큰 생성 (소비자 역할)
        // ══════════════════════════════════════════
        //  소비자가 앱에서 "결제" 버튼을 누르면
        //  QR 토큰이 Redis에 생성됩니다 (TTL 5초).
        //  이 단계는 모놀리스와 무관하게 QR Service + Redis만 사용합니다.
        // ──────────────────────────────────────────
        const qrStart = Date.now();
        const qrRes = http.post(
            `${BASE_URL}/api/qr`,
            JSON.stringify({
                walletId: walletId,
                bindStoreId: storeId,
            }),
            { headers: customerHeaders }
        );
        qrCreateTime.add(Date.now() - qrStart);

        const qrOk = check(qrRes, {
            'QR 생성: status 201': (r) => r.status === 201,
            'QR 생성: tokenId 존재': (r) => {
                try { return r.json().data && r.json().data.tokenId; }
                catch (e) { return false; }
            },
        });

        if (!qrOk) {
            paymentFailures.add(1);
            paymentSuccess.add(false);
            console.error(`[1단계 실패] QR 생성: ${qrRes.status} - ${qrRes.body}`);
            return;
        }

        const tokenId = qrRes.json('data').tokenId;

        // ══════════════════════════════════════════
        //  2단계: QR 스캔 (점주 역할) → 세션 토큰 발급
        // ══════════════════════════════════════════
        //  점주가 고객 QR을 스캔합니다. QR 토큰(TTL 10s)은 소비·삭제되고
        //  세션 토큰(TTL 3분)이 발급됩니다. 이후 initiate 는 세션 토큰으로 진행.
        // ──────────────────────────────────────────
        const scanStart = Date.now();
        const scanRes = http.post(
            `${BASE_URL}/api/qr/${tokenId}/scan`,
            null,
            { headers: ownerHeaders }
        );
        scanTime.add(Date.now() - scanStart);

        const scanOk = check(scanRes, {
            'Scan: status 200': (r) => r.status === 200,
            'Scan: sessionToken 존재': (r) => {
                try { return r.json().data && r.json().data.sessionToken; }
                catch (e) { return false; }
            },
        });

        if (!scanOk) {
            paymentFailures.add(1);
            paymentSuccess.add(false);
            console.error(`[2단계 실패] Scan: ${scanRes.status} - ${scanRes.body}`);
            return;
        }

        const sessionToken = scanRes.json('data').sessionToken;

        // 점주가 메뉴를 입력하는 시간 시뮬레이션 (0.3초)
        sleep(0.3);

        // ══════════════════════════════════════════
        //  3단계: 결제 요청 생성 — Intent (점주 역할)
        // ══════════════════════════════════════════
        //  점주가 QR을 스캔하고, 메뉴와 수량을 입력합니다.
        //  서버에서는 Store/Menu 정보를 조회해야 합니다.
        //
        //  ★ 캐싱 효과가 가장 크게 나타나는 단계 ★
        //  - NONE 모드: 모놀리스에 HTTP 호출 → 모놀리스가 바쁘면 느림
        //  - PUSH 모드: QR Service의 Redis 캐시에서 조회 → 빠름
        // ──────────────────────────────────────────
        const intentHeaders = Object.assign({}, ownerHeaders, {
            'Idempotency-Key': generateUUID(),
        });

        const intentStart = Date.now();
        const intentRes = http.post(
            `${BASE_URL}/cpqr/${sessionToken}/initiate`,
            JSON.stringify({
                storeId: storeId,
                orderItems: [{ menuId: menuId, quantity: 1 }],
            }),
            { headers: intentHeaders }
        );
        intentTime.add(Date.now() - intentStart);

        const intentOk = check(intentRes, {
            'Intent: status 201': (r) => r.status === 201,
            'Intent: intentId 존재': (r) => {
                try { return r.json().data && r.json().data.intentId; }
                catch (e) { return false; }
            },
        });

        if (!intentOk) {
            paymentFailures.add(1);
            paymentSuccess.add(false);
            console.error(`[3단계 실패] Intent: ${intentRes.status} - ${intentRes.body}`);
            return;
        }

        const intentId = intentRes.json('data').intentId;

        // 소비자가 결제 내역을 확인하는 시간을 시뮬레이션 (0.3초)
        sleep(0.3);

        // ══════════════════════════════════════════
        //  4단계: 결제 승인 — Approve (소비자 역할)
        // ══════════════════════════════════════════
        //  소비자가 결제 내역을 확인하고 PIN 번호를 입력합니다.
        //  서버에서는:
        //  1. PIN 검증 (모놀리스 동기 호출 — 보안상 캐싱 불가)
        //  2. 잔액 차감 (모놀리스 동기 호출 — 정합성 보장 필수)
        //  3. 비관적 락으로 동시성 제어
        //
        //  ★ 이 단계는 캐싱 모드와 관계없이 모놀리스 호출 필수 ★
        //  따라서 PUSH 모드에서도 완전히 빨라지지는 않습니다.
        // ──────────────────────────────────────────
        const approveHeaders = Object.assign({}, customerHeaders, {
            'Idempotency-Key': generateUUID(),
        });

        const approveStart = Date.now();
        const approveRes = http.post(
            `${BASE_URL}/payments/${intentId}/approve`,
            JSON.stringify({
                pin: TEST_DATA.PIN,
            }),
            { headers: approveHeaders }
        );
        approveTime.add(Date.now() - approveStart);

        const approveOk = check(approveRes, {
            'Approve: status 200': (r) => r.status === 200,
        });

        if (!approveOk) {
            paymentFailures.add(1);
            paymentSuccess.add(false);
            console.error(`[4단계 실패] Approve: ${approveRes.status} - ${approveRes.body}`);
            return;
        }

        paymentSuccess.add(true);
    });

    totalFlowTime.add(Date.now() - flowStart);

    // 다음 결제 플로우까지 대기 (실제 사용자가 다음 결제를 하기까지의 시간)
    sleep(1);
}

// ============================================================
//  결과 요약 출력
// ============================================================
export function handleSummary(data) {
    const qrP95 = data.metrics.qr_create_time?.values['p(95)'] || 0;
    const qrP99 = data.metrics.qr_create_time?.values['p(99)'] || 0;
    const intentP95 = data.metrics.intent_time?.values['p(95)'] || 0;
    const intentP99 = data.metrics.intent_time?.values['p(99)'] || 0;
    const approveP95 = data.metrics.approve_time?.values['p(95)'] || 0;
    const approveP99 = data.metrics.approve_time?.values['p(99)'] || 0;
    const totalP95 = data.metrics.total_flow_time?.values['p(95)'] || 0;
    const successRate = data.metrics.payment_success_rate?.values['rate'] || 0;
    const failures = data.metrics.payment_failures?.values['count'] || 0;

    console.log('\n');
    console.log('╔══════════════════════════════════════════════════════════════╗');
    console.log('║              QR 결제 성능 측정 결과                          ║');
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log('║                                                              ║');
    console.log(`║  1단계 QR 생성  (소비자)   p95: ${pad(qrP95)}ms  p99: ${pad(qrP99)}ms  ║`);
    console.log(`║  2단계 Intent   (점주)     p95: ${pad(intentP95)}ms  p99: ${pad(intentP99)}ms  ║`);
    console.log(`║  3단계 Approve  (소비자)   p95: ${pad(approveP95)}ms  p99: ${pad(approveP99)}ms  ║`);
    console.log('║                                                              ║');
    console.log(`║  전체 플로우               p95: ${pad(totalP95)}ms              ║`);
    console.log(`║  성공률:                   ${(successRate * 100).toFixed(1)}%                        ║`);
    console.log(`║  실패 수:                  ${failures}건                           ║`);
    console.log('║                                                              ║');
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log('║  Threshold 결과:                                             ║');
    console.log(`║  QR 생성 p95 < 500ms:  ${qrP95 < 500 ? '✅ PASS' : '❌ FAIL'}                          ║`);
    console.log(`║  Intent  p95 < 500ms:  ${intentP95 < 500 ? '✅ PASS' : '❌ FAIL'}                          ║`);
    console.log(`║  Approve p95 < 1000ms: ${approveP95 < 1000 ? '✅ PASS' : '❌ FAIL'}                          ║`);
    console.log('╚══════════════════════════════════════════════════════════════╝');
    console.log('\n');

    return {};
}

// ============================================================
//  헬퍼 함수
// ============================================================
function pad(num) {
    return num.toFixed(1).padStart(8);
}

function parseDuration(duration) {
    const match = duration.match(/^(\d+)(s|m|h)$/);
    if (!match) return 180; // 기본 3분
    const value = parseInt(match[1]);
    switch (match[2]) {
        case 's': return value;
        case 'm': return value * 60;
        case 'h': return value * 3600;
        default: return 180;
    }
}
