import http from "k6/http";
import { check } from "k6";
import { Trend, Rate } from "k6/metrics";

/**
 * ENV
 * - BASE_URL=http://localhost:8080
 * - AMOUNT=10000
 *
 * 분산 옵션
 * - STORE_ID=1 (고정) 또는 STORE_ID_MIN=1, STORE_ID_MAX=500
 * - CUSTOMER_ID=1 (고정) 또는 CUSTOMER_ID_MIN=1, CUSTOMER_ID_MAX=1000
 *
 * 인증 헤더(TestHeaderAuthenticationFilter)
 * - TEST_ROLE=CUSTOMER (default)
 *
 * 시나리오
 * - RPS, WARMUP_RPS, DURATION, WARMUP
 * - PREALLOC_VUS, MAX_VUS
 *
 * 타임아웃
 * - REQ_TIMEOUT=10s (default)
 *
 * (선택) Threshold 조절
 * - TH_FAIL_RATE=0.02
 * - TH_RESERVE_P95_MS=500 ...
 */

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const AMOUNT = Number(__ENV.AMOUNT || 10000);

const STORE_ID = __ENV.STORE_ID ? Number(__ENV.STORE_ID) : null;
const STORE_ID_MIN = Number(__ENV.STORE_ID_MIN || 1);
const STORE_ID_MAX = Number(__ENV.STORE_ID_MAX || 1);

const CUSTOMER_ID = __ENV.CUSTOMER_ID ? Number(__ENV.CUSTOMER_ID) : null;
const CUSTOMER_ID_MIN = Number(__ENV.CUSTOMER_ID_MIN || 1);
const CUSTOMER_ID_MAX = Number(__ENV.CUSTOMER_ID_MAX || 1);

const TEST_ROLE = (__ENV.TEST_ROLE || "CUSTOMER").toUpperCase();

const WARMUP = __ENV.WARMUP || "30s";
const DURATION = __ENV.DURATION || "5m";
const WARMUP_RPS = Number(__ENV.WARMUP_RPS || 50);
const RPS = Number(__ENV.RPS || 100);

const PREALLOC_VUS = Number(__ENV.PREALLOC_VUS || 200);
const MAX_VUS = Number(__ENV.MAX_VUS || 2000);

const REQ_TIMEOUT = __ENV.REQ_TIMEOUT || "10s";

function numEnv(key, def) {
    const v = Number(__ENV[key]);
    return Number.isFinite(v) && v > 0 ? v : def;
}

// 기본은 “낮게” 잡아둠 (필요하면 env로 올려)
const TH_FAIL_RATE = numEnv("TH_FAIL_RATE", 0.02); // 2%
const TH_RESERVE_P95 = numEnv("TH_RESERVE_P95_MS", 500);
const TH_RESERVE_P99 = numEnv("TH_RESERVE_P99_MS", 1500);
const TH_CONFIRM_P95 = numEnv("TH_CONFIRM_P95_MS", 800);
const TH_CONFIRM_P99 = numEnv("TH_CONFIRM_P99_MS", 2500);
const TH_E2E_P95 = numEnv("TH_E2E_P95_MS", 1200);
const TH_E2E_P99 = numEnv("TH_E2E_P99_MS", 3500);

export const options = {
    scenarios: {
        warmup: {
            executor: "constant-arrival-rate",
            rate: WARMUP_RPS,
            timeUnit: "1s",
            duration: WARMUP,
            preAllocatedVUs: PREALLOC_VUS,
            maxVUs: MAX_VUS,
            exec: "runE2E",
            gracefulStop: "30s",
        },
        measure: {
            executor: "constant-arrival-rate",
            rate: RPS,
            timeUnit: "1s",
            duration: DURATION,
            startTime: WARMUP,
            preAllocatedVUs: PREALLOC_VUS,
            maxVUs: MAX_VUS,
            exec: "runE2E",
            gracefulStop: "30s",
        },
    },
    thresholds: {
        app_fail: [`rate<${TH_FAIL_RATE}`],
        e2e_iteration_duration_ms: [`p(95)<${TH_E2E_P95}`, `p(99)<${TH_E2E_P99}`],
        "http_req_duration{name:prepayment_reserve}": [
            `p(95)<${TH_RESERVE_P95}`,
            `p(99)<${TH_RESERVE_P99}`,
        ],
        "http_req_duration{name:prepayment_confirm}": [
            `p(95)<${TH_CONFIRM_P95}`,
            `p(99)<${TH_CONFIRM_P99}`,
        ],
        "http_req_failed{name:prepayment_reserve}": [`rate<${TH_FAIL_RATE}`],
        "http_req_failed{name:prepayment_confirm}": [`rate<${TH_FAIL_RATE}`],
    },
};

const appFail = new Rate("app_fail");
const reserveDuration = new Trend("reserve_duration_ms", true);
const confirmDuration = new Trend("confirm_duration_ms", true);
const e2eDuration = new Trend("e2e_iteration_duration_ms", true);

function randInt(min, max) {
    return Math.floor(Math.random() * (max - min + 1)) + min;
}

function pickStoreId() {
    if (STORE_ID !== null) return STORE_ID;
    return randInt(STORE_ID_MIN, STORE_ID_MAX);
}

function pickCustomerId() {
    if (CUSTOMER_ID !== null) return CUSTOMER_ID;
    return randInt(CUSTOMER_ID_MIN, CUSTOMER_ID_MAX);
}

function paymentKeyFor(orderId) {
    // WireMock이 request.paymentKey를 그대로 응답으로 돌려주므로, 문자열 형태면 충분
    return `PK_${orderId}_${__VU}_${__ITER}`;
}

function commonHeaders(customerId) {
    return {
        "Content-Type": "application/json",
        "X-TEST-CUSTOMER-ID": String(customerId),
        "X-TEST-ROLE": TEST_ROLE,
    };
}

export function setup() {
    console.log(`MAX_VUS=${__ENV.MAX_VUS}, PREALLOC_VUS=${__ENV.PREALLOC_VUS}`);
    // 기존 setup 로직...
    return {};
}

export function runE2E() {
    const startedAt = Date.now();
    let ok = true;

    const storeId = pickStoreId();
    const customerId = pickCustomerId();
    const headers = commonHeaders(customerId);

    // 1) reserve
    const reserveUrl = `${BASE_URL}/api/v1/stores/${storeId}/prepayment/reserve`;
    let orderId = null;

    try {
        const res = http.post(
            reserveUrl,
            JSON.stringify({ amount: AMOUNT }),
            {
                headers,
                timeout: REQ_TIMEOUT,
                tags: { name: "prepayment_reserve" },
            }
        );

        reserveDuration.add(res.timings.duration);

        const reserveOk = check(res, {
            "reserve status is 201": (r) => r.status === 201,
        });

        if (!reserveOk) ok = false;

        if (reserveOk) {
            let body = null;
            try {
                body = res.json();
            } catch (_) {
                body = null;
            }

            orderId = body?.data?.orderId || null;

            const hasOrderId = check(body, {
                "reserve response has data.orderId": (b) =>
                    typeof b?.data?.orderId === "string" && b.data.orderId.length > 0,
            });

            if (!hasOrderId) ok = false;
        }
    } catch (_) {
        ok = false;
    }

    // reserve 실패면 confirm 스킵
    if (!orderId) {
        appFail.add(true);
        e2eDuration.add(Date.now() - startedAt);
        return;
    }

    // 2) confirm (같은 storeId로!)
    const confirmUrl = `${BASE_URL}/api/v1/stores/${storeId}/prepayment/confirm`;
    const paymentKey = paymentKeyFor(orderId);

    try {
        const res = http.post(
            confirmUrl,
            JSON.stringify({
                paymentKey,
                orderId,
                amount: AMOUNT,
            }),
            {
                headers,
                timeout: REQ_TIMEOUT,
                tags: { name: "prepayment_confirm" },
            }
        );

        confirmDuration.add(res.timings.duration);

        const confirmOk = check(res, {
            "confirm status is 200 or 201": (r) => r.status === 200 || r.status === 201,
        });

        if (!confirmOk) ok = false;

        if (confirmOk) {
            let body = null;
            try {
                body = res.json();
            } catch (_) {
                body = null;
            }

            const hasTx = check(body, {
                "confirm response has transactionId": (b) =>
                    typeof b?.data?.transactionId === "number" && b.data.transactionId > 0,
                "confirm response has transactionUniqueNo": (b) =>
                    typeof b?.data?.transactionUniqueNo === "string" &&
                    b.data.transactionUniqueNo.length > 0,
            });

            if (!hasTx) ok = false;
        }
    } catch (_) {
        ok = false;
    }

    appFail.add(!ok);
    e2eDuration.add(Date.now() - startedAt);
}
