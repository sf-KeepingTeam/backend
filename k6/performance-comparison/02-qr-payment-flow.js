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
 *  k6 run -e QR_BASE_URL=http://<payment Private IP>:8081 02-qr-payment-flow.js
 *
 *  VU 수와 시간을 변경하려면:
 *  k6 run -e BASE_URL=http://<NGINX_IP> -e QR_VUS=100 -e QR_DURATION=5m 02-qr-payment-flow.js
 */

import http from 'k6/http';
import { check, sleep, group } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';
import {
    MONO_BASE_URL,
    QR_BASE_URL,
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
const pinTokenTime = new Trend('pin_token_time', true);     // 3.5단계: PIN 토큰 발급

// ★ 세션 토큰 판정 지표 — 발급 횟수 ÷ 결제 건수 가 요청 A 의 핵심 근거다
const pinTokenIssues = new Counter('pin_token_issues');     // verify-token 호출 수
const pinRateLimited = new Counter('pin_rate_limited_429'); // ⚠️ 결제 실패로 세지 말 것
const pinTokenRejected = new Counter('pin_token_rejected'); // 401/403/409 (소진·만료·폐기)
const pinTokenRetries = new Counter('pin_token_retries');   // 재발급 후 재시도 성공
const approveTime = new Trend('approve_time', true);       // 4단계: 결제 승인
const totalFlowTime = new Trend('total_flow_time', true);  // 전체 플로우 소요시간

const paymentSuccess = new Rate('payment_success_rate');    // 결제 성공률
const paymentFailures = new Counter('payment_failures');    // 결제 실패 수

// ============================================================
//  부하 설정
// ============================================================
const QR_MAX_VUS = parseInt(__ENV.QR_VUS || '50');
const QR_DURATION = __ENV.QR_DURATION || '3m';

// ── QR_MODE ────────────────────────────────────────────────────────────────
//  'ramp'     (기본, 기존 동작) 0 → VU/2 → VU → VU 유지 → 0 계단
//  'constant' 처음부터 끝까지 목표 VU 고정
//
//  ★ 왜 constant 가 필요한가 (2026-08-22 발견)
//
//  ramp 모드에서 "결제 30 VU · 4m" 라고 적었지만 실제로는:
//    0→15 (80초) · 15→30 (80초) · 30 유지 (80초) · 30→0 (30초)  = 총 270초
//  평균 VU 는 약 19.4 이고, 30 VU 인 구간은 270초 중 80초뿐이다.
//  그런데 k6 가 출력하는 p95 는 **전 구간 합산**이라 램프업의 한산한 구간이
//  섞여 들어가 **지연이 실제보다 좋게 나온다.**
//
//  또 배경부하(constant, DUR)와 길이가 달라 러닝 후반에 배경부하가 먼저
//  끝나버린다. 두 부하의 창을 맞추려면 결제도 constant 여야 한다.
const QR_MODE = __ENV.QR_MODE || 'ramp';

// ── PIN_MODE ────────────────────────────────────────────────────────────────
//  'pin'   (기본, 기존 동작) approve 에 평문 PIN 을 실어 보낸다.
//                            qr-service 가 monolith 로 pin-verify 를 동기 호출한다.
//  'token' (v3 개선)         approve 전에 monolith 에서 PIN 토큰을 발급받아 첨부한다.
//                            qr-service 는 서명을 로컬 검증하므로 monolith 왕복이 없다.
//
//  ★ v3 에서 qr-service 의 ApproveRequest 에 pinToken 필드가 추가됐다.
//    PIN_MODE=pin 으로 두면 payment.pin.token-enabled=true 여도 토큰 경로를 타지 않는다.
//    → PAYMENT_PIN_TOKEN_ENABLED=true 로 측정할 때는 반드시 PIN_MODE=token 으로 실행할 것.
//
//  ⚠️ 이 모드는 「개선」이 아니라 「이동」일 수 있다.
//    토큰이 intent 단위로 발급되므로 결제 1건당 Argon2 계산 횟수는 줄지 않는다.
//    approve() 안에서 하던 것을 클라이언트가 미리 하는 것으로 위치만 바뀐다.
//    → approve_time 은 크게 줄지만 total_flow_time 은 덜 줄어든다.
//      판정은 total_flow_time 과 처리량(초당 건수)으로 한다.
const PIN_MODE = __ENV.PIN_MODE || 'pin';

// ── PIN_MODE=session (v3 후속) ──────────────────────────────────────────────
//  'token'   은 결제 1건마다 verify-token 을 부른다 → Argon2 횟수가 그대로다.
//            1차 측정에서 "개선이 아니라 이동" 판정을 받은 이유 (result.md §7-4 ③).
//  'session' 은 VU 당 토큰 1개를 들고 다니며 여러 결제에 재사용한다.
//            이때 비로소 Argon2 호출 횟수가 실제로 줄어든다.
//
//  ★ 발급 요청에 intentPublicId 를 넣지 않는다. 넣으면 intent 토큰이 나와서
//    세션 토큰을 만들어놓고 F4 를 다시 재는 꼴이 된다.
//
//  재발급 조건: 사용 횟수 소진 / TTL 임박 / approve 가 토큰 사유로 거절
const PIN_SESSION_MAX_USES = parseInt(__ENV.PIN_SESSION_MAX_USES || '5');
// 서버 TTL 180초 − 여유 30초
const PIN_SESSION_MAX_AGE_MS = parseInt(__ENV.PIN_SESSION_MAX_AGE_MS || '150000');

// ── VU-local 세션 상태 (k6 는 VU 마다 별도 JS 컨텍스트라 이터레이션 간 유지된다) ──
let sessionToken = null;
let sessionUses = 0;
let sessionIssuedAt = 0;

// 램프업 시간을 전체의 1/3로 설정
const rampDuration = Math.max(30, Math.floor(parseDuration(QR_DURATION) / 3));

const qrScenario = QR_MODE === 'constant'
    ? { executor: 'constant-vus', vus: QR_MAX_VUS, duration: QR_DURATION }
    : {
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
      };

export const options = {
    scenarios: {
        qr_payment: qrScenario,
    },
    thresholds: {
        'qr_create_time': ['p(95)<500'],     // QR 생성: 500ms 이내
        'intent_time': ['p(95)<500'],         // 결제 요청: 500ms 이내
        'approve_time': ['p(95)<1000'],       // 결제 승인: 1초 이내
        'http_req_failed': ['rate<0.05'],     // 전체 에러율: 5% 이내
    },
};

// ============================================================
//  세션 토큰 헬퍼 (PIN_MODE=session)
// ============================================================
function issueSessionToken(headers) {
    const st = Date.now();
    const res = http.post(
        `${MONO_BASE_URL}/customers/pin/verify-token`,
        JSON.stringify({ pin: TEST_DATA.PIN }),   // ★ intentPublicId 없음 = 세션 토큰
        { headers }
    );
    pinTokenTime.add(Date.now() - st);
    pinTokenIssues.add(1);

    // 429 는 rate limit(10/분)이지 결제 실패가 아니다. 반드시 분리해서 센다.
    if (res.status === 429) { pinRateLimited.add(1); return null; }
    if (res.status !== 200) {
        console.error(`[세션토큰 발급 실패] ${res.status} - ${res.body}`);
        return null;
    }
    try {
        const t = res.json('data').pinToken;
        if (!t) return null;
        sessionToken = t; sessionUses = 0; sessionIssuedAt = Date.now();
        return t;
    } catch (e) { return null; }
}

function getSessionToken(headers) {
    const aged = sessionIssuedAt > 0 && (Date.now() - sessionIssuedAt) > PIN_SESSION_MAX_AGE_MS;
    if (!sessionToken || sessionUses >= PIN_SESSION_MAX_USES || aged) {
        return issueSessionToken(headers);
    }
    return sessionToken;
}

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
            `${QR_BASE_URL}/api/qr`,
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
            `${QR_BASE_URL}/api/qr/${tokenId}/scan`,
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
            `${QR_BASE_URL}/cpqr/${sessionToken}/initiate`,
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
        // ══════════════════════════════════════════
        //  3.5단계: PIN 토큰 발급 (PIN_MODE=token 일 때만) — 소비자 역할
        // ══════════════════════════════════════════
        //  소비자가 PIN 을 입력하면 monolith 가 Argon2 로 검증하고
        //  intent 에 바인딩된 단기 서명 토큰(JWT)을 준다.
        //  이후 approve 는 이 토큰만 보내고, qr-service 는 로컬에서 서명만 검증한다.
        //
        //  ★ 대상이 monolith(8080)다. QR_BASE_URL 이 아니라 MONO_BASE_URL.
        //  ★ intentPublicId = 경로의 intentId (둘 다 같은 UUID)
        // ──────────────────────────────────────────
        let pinToken = null;
        if (PIN_MODE === 'session') {
            pinToken = getSessionToken(customerHeaders);
            if (!pinToken) {
                paymentFailures.add(1);
                paymentSuccess.add(false);
                return;   // 발급 실패(429 포함) — 위에서 이미 카운트됨
            }
        } else if (PIN_MODE === 'token') {
            const pinTokenStart = Date.now();
            const pinTokenRes = http.post(
                `${MONO_BASE_URL}/customers/pin/verify-token`,
                JSON.stringify({
                    pin: TEST_DATA.PIN,
                    intentPublicId: intentId,
                }),
                { headers: customerHeaders }
            );
            pinTokenTime.add(Date.now() - pinTokenStart);

            const pinTokenOk = check(pinTokenRes, {
                'PinToken: status 200': (r) => r.status === 200,
                'PinToken: pinToken 존재': (r) => {
                    try { return r.json().data && r.json().data.pinToken; }
                    catch (e) { return false; }
                },
            });

            if (!pinTokenOk) {
                paymentFailures.add(1);
                paymentSuccess.add(false);
                console.error(`[3.5단계 실패] PinToken: ${pinTokenRes.status} - ${pinTokenRes.body}`);
                return;
            }

            pinToken = pinTokenRes.json('data').pinToken;
        }

        // ══════════════════════════════════════════
        const approveHeaders = Object.assign({}, customerHeaders, {
            'Idempotency-Key': generateUUID(),
        });

        // PIN_MODE 에 따라 평문 PIN 또는 토큰을 보낸다
        const usingToken = (PIN_MODE === 'token' || PIN_MODE === 'session');
        const approveBody = usingToken
            ? { pinToken: pinToken }
            : { pin: TEST_DATA.PIN };

        const approveStart = Date.now();
        let approveRes = http.post(
            `${QR_BASE_URL}/payments/${intentId}/approve`,
            JSON.stringify(approveBody),
            { headers: approveHeaders }
        );

        // ── 세션 토큰이 소진·만료·폐기로 거절되면 재발급 후 1회 재시도 ──────────
        //    이건 '실패'가 아니라 정상 운영 경로다. 진짜 실패와 구분해서 센다.
        if (PIN_MODE === 'session'
            && (approveRes.status === 401 || approveRes.status === 403 || approveRes.status === 409)) {
            pinTokenRejected.add(1);
            sessionToken = null; sessionUses = 0; sessionIssuedAt = 0;
            const fresh = getSessionToken(customerHeaders);
            if (fresh) {
                approveRes = http.post(
                    `${QR_BASE_URL}/payments/${intentId}/approve`,
                    JSON.stringify({ pinToken: fresh }),
                    { headers: approveHeaders }
                );
                pinTokenRetries.add(1);
            }
        }
        // approve_time 은 재시도를 포함한 벽시계 시간이다 (소비자가 실제로 기다린 시간)
        approveTime.add(Date.now() - approveStart);

        if (PIN_MODE === 'session' && approveRes.status >= 200 && approveRes.status < 300) {
            sessionUses += 1;
        }

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
    const pinTokenP95 = data.metrics.pin_token_time?.values['p(95)'] || 0;
    const tokenIssues = data.metrics.pin_token_issues?.values['count'] || 0;
    const rateLimited = data.metrics.pin_rate_limited_429?.values['count'] || 0;
    const tokenRejected = data.metrics.pin_token_rejected?.values['count'] || 0;
    const tokenRetries = data.metrics.pin_token_retries?.values['count'] || 0;
    const iterations = data.metrics.iterations?.values['count'] || 0;
    const successRate = data.metrics.payment_success_rate?.values['rate'] || 0;
    const failures = data.metrics.payment_failures?.values['count'] || 0;

    console.log('\n');
    console.log('╔══════════════════════════════════════════════════════════════╗');
    console.log('║              QR 결제 성능 측정 결과                          ║');
    console.log('╠══════════════════════════════════════════════════════════════╣');
    console.log('║                                                              ║');
    console.log(`║  1단계 QR 생성  (소비자)   p95: ${pad(qrP95)}ms  p99: ${pad(qrP99)}ms  ║`);
    console.log(`║  2단계 Intent   (점주)     p95: ${pad(intentP95)}ms  p99: ${pad(intentP99)}ms  ║`);
    if (PIN_MODE === 'token') {
        console.log(`║  3.5단계 PIN토큰(소비자)   p95: ${pad(pinTokenP95)}ms                   ║`);
    }
    console.log(`║  3단계 Approve  (소비자)   p95: ${pad(approveP95)}ms  p99: ${pad(approveP99)}ms  ║`);
    console.log('║                                                              ║');
    console.log(`║  전체 플로우               p95: ${pad(totalP95)}ms              ║`);
    console.log(`║  PIN 모드: ${PIN_MODE.padEnd(8)}                                      ║`);
    if (PIN_MODE === 'session') {
        const ratio = iterations > 0 ? (tokenIssues / iterations) : 0;
        console.log('║                                                              ║');
        console.log(`║  ★ verify-token 호출: ${String(tokenIssues).padStart(6)} / 결제 ${String(iterations).padStart(6)} 건        ║`);
        console.log(`║  ★ 호출/결제 비율:    ${ratio.toFixed(3).padStart(6)}   (기대 ≈ ${(1/PIN_SESSION_MAX_USES).toFixed(3)})       ║`);
        console.log(`║    429 rate limit:    ${String(rateLimited).padStart(6)} 건  ← 결제실패 아님    ║`);
        console.log(`║    토큰 거절(401/403/409): ${String(tokenRejected).padStart(5)} 건               ║`);
        console.log(`║    재발급 후 재시도:  ${String(tokenRetries).padStart(6)} 건               ║`);
    }
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
