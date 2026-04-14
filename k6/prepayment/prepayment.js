import http from 'k6/http';
import { check, sleep } from 'k6';
import { randomString, uuidv4 } from 'https://jslib.k6.io/k6-utils/1.4.0/index.js';

// ====== 환경변수 ======
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const TOKEN = __ENV.TOKEN || ''; // CUSTOMER access token
const STORE_ID = __ENV.STORE_ID || '1';
const MODE = __ENV.MODE || 'RESERVE_THEN_CONFIRM'; // RESERVE_ONLY | RESERVE_THEN_CONFIRM | CONFIRM_ONLY
const AMOUNT = Number(__ENV.AMOUNT || 10000);

// CONFIRM_ONLY 모드일 때 미리 준비한 예약 목록(json) 사용 가능
// 예: [{"orderId":"ORDER_...","amount":10000},{"orderId":"ORDER_...","amount":20000}]
const RESERVATIONS_JSON_PATH = __ENV.RESERVATIONS || ''; // ./reservations.json

const TEST_CUSTOMER_ID = __ENV.TEST_CUSTOMER_ID || '1';
const TEST_ROLE = __ENV.TEST_ROLE || 'CUSTOMER';

function authHeaders() {
    return {
        headers: {
            'Content-Type': 'application/json',
            'X-TEST-CUSTOMER-ID': TEST_CUSTOMER_ID,
            'X-TEST-ROLE': TEST_ROLE,
        },
    };
}

// ApiResponse 래퍼에서 data 뽑기(프로젝트마다 필드명이 다를 수 있어 방어적으로 처리)
function extractData(json) {
    if (!json) return null;
    return json.data ?? json.body ?? json.result ?? json.response ?? null;
}

export const options = {
    scenarios: {
        // (A) reserve를 램프업(0 -> target)으로 올리는 시나리오
        reserve_ramp: {
            executor: 'ramping-arrival-rate',
            startRate: 0,
            timeUnit: '1s',
            preAllocatedVUs: 50,
            maxVUs: 300,
            stages: [
                { target: 20, duration: '1m' },
                { target: 50, duration: '2m' },
                { target: 100, duration: '3m' },
                { target: 150, duration: '3m' },
                { target: 200, duration: '3m' },
                { target: 0, duration: '1m' },
            ],
            exec: 'reserveScenario',
        },

        // (B) confirm을 “고정 RPS”로 유지하는 시나리오(간섭 실험용)
        confirm_constant: {
            executor: 'constant-arrival-rate',
            rate: 30,         // QR 간섭 실험처럼 '고정'을 원하면 여기 고정
            timeUnit: '1s',
            duration: '10m',
            preAllocatedVUs: 50,
            maxVUs: 300,
            exec: 'confirmScenario',
        },
    },

    thresholds: {
        // 전체 요청 기준(필요하면 endpoint tag로 더 쪼갤 수 있음)
        http_req_failed: ['rate<0.01'], // 실패율 < 1%
        http_req_duration: ['p(95)<800', 'p(99)<2000'], // 초기 가설(토스 0ms일 때는 더 타이트하게)
    },
};

// ====== CONFIRM_ONLY용 예약 데이터 로딩 ======
let reservations = null;
if (RESERVATIONS_JSON_PATH) {
    reservations = JSON.parse(open(RESERVATIONS_JSON_PATH));
}

function reserveOnce() {
    const url = `${BASE_URL}/api/v1/stores/${STORE_ID}/prepayment/reserve`;
    const payload = JSON.stringify({
        amount: AMOUNT,
        orderName: `k6-${AMOUNT}`,
    });

    const res = http.post(url, payload, authHeaders());

    const ok = check(res, {
        'reserve status is 201': (r) => r.status === 201,
    });

    if (!ok) return null;

    const json = res.json();
    const data = extractData(json);
    if (!data || !data.orderId) return null;

    return { orderId: data.orderId, amount: data.amount ?? AMOUNT };
}

function confirmOnce(orderId, amount) {
    const url = `${BASE_URL}/api/v1/stores/${STORE_ID}/prepayment/confirm`;

    // paymentKey는 매 요청 고유하게(트랜잭션 unique 충돌 방지)
    const paymentKey = `pay_${uuidv4()}`;

    const payload = JSON.stringify({
        paymentKey,
        orderId,
        amount,
    });

    const res = http.post(url, payload, authHeaders());

    check(res, {
        'confirm status is 200/201': (r) => r.status === 200 || r.status === 201,
    });

    return res;
}

// ====== 시나리오 함수 ======
export function reserveScenario() {
    if (MODE !== 'RESERVE_ONLY' && MODE !== 'RESERVE_THEN_CONFIRM') {
        sleep(1);
        return;
    }

    const r = reserveOnce();
    if (!r) return;

    // RESERVE_THEN_CONFIRM이면 바로 confirm까지 수행
    if (MODE === 'RESERVE_THEN_CONFIRM') {
        confirmOnce(r.orderId, r.amount);
    }
}

export function confirmScenario() {
    if (MODE !== 'CONFIRM_ONLY') {
        // 간섭 실험 시 “confirm_constant” 시나리오를 꺼도 되고,
        // RESERVE_THEN_CONFIRM만으로도 충분히 테스트 가능
        sleep(1);
        return;
    }

    if (!reservations || reservations.length === 0) return;

    // VU별로 서로 다른 index를 써서 분산(대충)
    const idx = (__VU * 100000 + __ITER) % reservations.length;
    const item = reservations[idx];

    confirmOnce(item.orderId, item.amount);
}
