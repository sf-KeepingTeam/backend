/**
 * ============================================================
 *  01-background-load.js — 배경 부하 스크립트
 * ============================================================
 *
 *  [목적]
 *  모놀리스 서버에 "다른 기능이 바쁜 상황"을 만들어주는 스크립트입니다.
 *  이 스크립트가 모놀리스를 바쁘게 만드는 동안,
 *  별도 터미널에서 02-qr-payment-flow.js를 실행하여 QR 결제 성능을 측정합니다.
 *
 *  [하는 일]
 *  각 VU(가상 사용자)가 아래 API를 반복 호출하여 모놀리스에 부하를 줍니다:
 *  - Wallet 잔액 조회 (개인/통합)
 *  - Store 정보 조회
 *  - Menu 목록 조회
 *
 *  [실행 방법]
 *  k6 run -e BASE_URL=http://<NGINX_IP> 01-background-load.js
 *
 *  VU 수와 시간을 변경하려면:
 *  k6 run -e BASE_URL=http://<NGINX_IP> -e BG_VUS=100 -e BG_DURATION=10m 01-background-load.js
 */

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Trend, Counter } from 'k6/metrics';
import {
    BASE_URL,
    getTestHeaders,
    randomCustomerId,
    randomStoreId,
} from './common.js';

// ============================================================
//  커스텀 메트릭
// ============================================================
// 배경 부하의 응답시간도 측정하여, 모놀리스가 얼마나 바쁜지 확인할 수 있습니다.
const walletDuration = new Trend('bg_wallet_duration', true);
const storeDuration = new Trend('bg_store_duration', true);
const menuDuration = new Trend('bg_menu_duration', true);
const bgErrors = new Counter('bg_errors');

// ============================================================
//  부하 설정
// ============================================================
// 환경변수로 VU 수와 시간을 조절할 수 있습니다.
const BG_VUS = parseInt(__ENV.BG_VUS || '50');
const BG_DURATION = __ENV.BG_DURATION || '5m';

export const options = {
    scenarios: {
        background_load: {
            executor: 'constant-vus',
            vus: BG_VUS,
            duration: BG_DURATION,
        },
    },
    thresholds: {
        // 배경 부하는 threshold를 넉넉하게 설정 (측정이 목적이 아님)
        http_req_failed: ['rate<0.30'],  // 30% 이하 실패
    },
};

// ============================================================
//  메인 함수 — 각 VU가 반복 실행합니다
// ============================================================
export default function () {
    const customerId = randomCustomerId();
    const storeId = randomStoreId();
    const headers = getTestHeaders(customerId, 'CUSTOMER');

    // ──────────────────────────────────────────────
    // 1. Wallet 개인 잔액 조회
    //    실제 사용자가 앱에서 잔액을 확인하는 행동을 시뮬레이션
    // ──────────────────────────────────────────────
    const walletStart = Date.now();
    const walletRes = http.get(
        `${BASE_URL}/wallets/individual/balance?page=0&size=10`,
        { headers }
    );
    walletDuration.add(Date.now() - walletStart);

    if (walletRes.status !== 200) {
        bgErrors.add(1);
    }

    sleep(0.3);

    // ──────────────────────────────────────────────
    // 2. Wallet 통합 잔액 조회
    //    개인 + 모임 지갑을 한번에 조회하는 API
    // ──────────────────────────────────────────────
    const bothStart = Date.now();
    const bothRes = http.get(
        `${BASE_URL}/wallets/both/balance?page=0&size=10`,
        { headers }
    );
    walletDuration.add(Date.now() - bothStart);

    sleep(0.3);

    // ──────────────────────────────────────────────
    // 3. Store 정보 조회
    //    매장 정보를 조회하는 API (QR 결제 시에도 필요한 데이터)
    // ──────────────────────────────────────────────
    const storeStart = Date.now();
    const storeRes = http.get(
        `${BASE_URL}/stores/${storeId}`,
        { headers }
    );
    storeDuration.add(Date.now() - storeStart);

    check(storeRes, {
        'store: status 200': (r) => r.status === 200,
    });

    sleep(0.3);

    // ──────────────────────────────────────────────
    // 4. Menu 목록 조회
    //    매장의 메뉴 목록 조회 (QR 결제 시에도 필요한 데이터)
    // ──────────────────────────────────────────────
    const menuStart = Date.now();
    const menuRes = http.get(
        `${BASE_URL}/stores/${storeId}/menus`,
        { headers }
    );
    menuDuration.add(Date.now() - menuStart);

    check(menuRes, {
        'menu: status 200': (r) => r.status === 200,
    });

    // ──────────────────────────────────────────────
    // 대기 후 다시 반복
    // 실제 사용자가 앱을 둘러보는 시간을 시뮬레이션
    // ──────────────────────────────────────────────
    sleep(0.5);
}

// ============================================================
//  결과 요약 출력
// ============================================================
export function handleSummary(data) {
    const walletP95 = data.metrics.bg_wallet_duration?.values['p(95)'] || 0;
    const storeP95 = data.metrics.bg_store_duration?.values['p(95)'] || 0;
    const menuP95 = data.metrics.bg_menu_duration?.values['p(95)'] || 0;
    const errors = data.metrics.bg_errors?.values['count'] || 0;

    console.log('\n========== 배경 부하 결과 요약 ==========');
    console.log(`VU: ${BG_VUS}명, 시간: ${BG_DURATION}`);
    console.log(`\nWallet API p(95): ${walletP95.toFixed(2)}ms`);
    console.log(`Store API p(95):  ${storeP95.toFixed(2)}ms`);
    console.log(`Menu API p(95):   ${menuP95.toFixed(2)}ms`);
    console.log(`에러 수:          ${errors}`);
    console.log('==========================================\n');

    return {};
}
