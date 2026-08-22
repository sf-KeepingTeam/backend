/**
 * ============================================================
 *  confirm-race.js - 충전 confirm 동시성 결함 재현 스크립트
 * ============================================================
 *
 *  [목적 - 학습용]
 *  "같은 충전 예약(orderId)에 confirm 요청이 동시에 여러 개 도착하면
 *   포인트가 두 번 적립될 수 있는가?" 를 재현한다.
 *
 *  [왜 재현되나 - 결함 원리]
 *  PrepaymentService.confirmPayment 는 멱등키 가드가 없고,
 *  '예약 상태(PENDING/COMPLETED)' 검사만으로 중복을 막는다.
 *  이 검사는 read-then-write(읽고 -> 적립 -> 상태변경) 라서,
 *  두 요청이 거의 동시에 들어오면 둘 다 'PENDING'을 읽고
 *  둘 다 적립을 진행하는 race 윈도우가 열린다.
 *
 *  [측정/판정]
 *  k6 는 HTTP 응답까지만 본다. 이 스크립트는 "성공(2xx) 응답이 몇 개인지"만 센다.
 *  진짜 이중 적립 여부는 실행 후 DB에서 잔액/거래건수로 확인한다(가이드 참고).
 *  - 2xx 가 2건 이상이면 -> 이중 적립 의심 (race 재현 성공 가능성 높음)
 *  - 2xx 가 1건, 나머지 4xx(상태 충돌) -> 가드가 막아낸 것
 *
 *  [실행]
 *  k6 run -e BASE_URL=http://localhost:8080 -e STORE_ID=1 -e TEST_CUSTOMER_ID=1 -e AMOUNT=10000 -e VUS=30 confirm-race.js
 *
 *  ※ 백엔드는 loadtest 프로필(백도어 인증 헤더 허용)로 로컬 기동되어 있어야 한다.
 * ============================================================
 */

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// -- 환경변수 --------------------------------------------------
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const STORE_ID = __ENV.STORE_ID || '1';
const TEST_CUSTOMER_ID = __ENV.TEST_CUSTOMER_ID || '1';
const TEST_ROLE = __ENV.TEST_ROLE || 'CUSTOMER';
const AMOUNT = Number(__ENV.AMOUNT || 10000);
const VUS = parseInt(__ENV.VUS || '30');

// -- 커스텀 메트릭 ---------------------------------------------
const confirmSuccess = new Counter('confirm_success_2xx');
const confirmConflict = new Counter('confirm_conflict_4xx');
const confirmError = new Counter('confirm_error_5xx');

function authHeaders() {
    // monolith LoadTestAuthenticationFilter 가 읽는 헤더명: X-Test-User-Id / X-Test-Role
    return {
        headers: {
            'Content-Type': 'application/json',
            'X-Test-User-Id': TEST_CUSTOMER_ID,
            'X-Test-Role': TEST_ROLE,
        },
    };
}

export const options = {
    scenarios: {
        confirm_race: {
            executor: 'per-vu-iterations',
            vus: VUS,
            iterations: 1,
            maxDuration: '30s',
        },
    },
};

// setup(): 충전 예약 1개 생성 -> 모든 VU 가 이 orderId 를 동시에 confirm
export function setup() {
    const url = `${BASE_URL}/api/v1/stores/${STORE_ID}/prepayment/reserve`;
    const payload = JSON.stringify({ amount: AMOUNT, orderName: `race-${AMOUNT}` });

    const res = http.post(url, payload, authHeaders());
    const ok = check(res, { 'reserve 201': (r) => r.status === 201 });
    if (!ok) {
        throw new Error(`예약 생성 실패: ${res.status} - ${res.body}`);
    }

    const data = res.json().data;
    const amount = (data.amount === undefined || data.amount === null) ? AMOUNT : data.amount;
    console.log(`[setup] 예약 생성 완료 - orderId=${data.orderId}, amount=${amount}`);
    return { orderId: data.orderId, amount: amount };
}

// 메인: 모든 VU 가 같은 orderId 로 confirm 을 동시에 호출 (paymentKey 만 매번 고유)
export default function (data) {
    const url = `${BASE_URL}/api/v1/stores/${STORE_ID}/prepayment/confirm`;
    const payload = JSON.stringify({
        paymentKey: `pay_${uuidv4()}`,
        orderId: data.orderId,
        amount: data.amount,
    });

    const res = http.post(url, payload, authHeaders());

    if (res.status >= 200 && res.status < 300) {
        confirmSuccess.add(1);
    } else if (res.status >= 400 && res.status < 500) {
        confirmConflict.add(1);
    } else {
        confirmError.add(1);
    }

    check(res, { 'confirm 응답 수신': (r) => r.status !== 0 });
    console.log(`[VU ${__VU}] confirm -> ${res.status}`);
}

function metricCount(data, name) {
    const m = data.metrics[name];
    if (m && m.values && typeof m.values.count === 'number') return m.values.count;
    return 0;
}

export function handleSummary(data) {
    const s = metricCount(data, 'confirm_success_2xx');
    const c = metricCount(data, 'confirm_conflict_4xx');
    const e = metricCount(data, 'confirm_error_5xx');

    console.log('\n====================  CONFIRM RACE 결과  ====================');
    console.log(`  동시 요청 수(VUS)   : ${VUS}`);
    console.log(`  성공(2xx)           : ${s}`);
    console.log(`  거절(4xx, 상태충돌) : ${c}`);
    console.log(`  서버에러(5xx)       : ${e}`);
    console.log('  ----------------------------------------------------------');
    if (s >= 2) {
        console.log('  [!] 성공 2건 이상 -> 이중 적립 의심! DB 잔액/거래건수를 확인하세요.');
    } else {
        console.log('  [OK] 성공 1건 -> 가드가 막아낸 것으로 보임. (그래도 DB 확인 권장)');
    }
    console.log('  -> 다음: 가이드의 DB 검증 SQL 로 실제 적립 횟수 확인');
    console.log('============================================================\n');
    return {};
}
