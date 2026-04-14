import http from "k6/http";
import { check, sleep } from "k6";
import { Trend } from "k6/metrics";

// ====== 환경변수 ======
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const STORE_ID = __ENV.STORE_ID || "1";
const CUSTOMER_ID = __ENV.CUSTOMER_ID || "1";
const AMOUNT = Number(__ENV.AMOUNT || 10000);

// 반복 간 텀(초) - 너무 공격적으로 치면 왜곡될 수 있어서 기본 0.2s
const PACE_SEC = Number(__ENV.PACE_SEC || 0.2);

// 실행 모드: smoke | load
const RUN = (__ENV.RUN || "smoke").toLowerCase();

// 실패 시 바디까지 찍고 싶으면 DEBUG=true
const DEBUG = (__ENV.DEBUG || "false").toLowerCase() === "true";

// 커스텀 지표
const reserveTrend = new Trend("reserve_duration_ms");

function buildParams() {
    return {
        headers: {
            "Content-Type": "application/json",
            "X-TEST-CUSTOMER-ID": String(CUSTOMER_ID),
            "X-TEST-ROLE": "CUSTOMER",

            // 서버 로그 추적용(있어도 무해)
            "X-REQUEST-ID": `${__VU}-${__ITER}-${Date.now()}`,
        },
        tags: { name: "prepayment_reserve" },
    };
}

function reserveOnce() {
    const url = `${BASE_URL}/api/v1/stores/${STORE_ID}/prepayment/reserve`;
    const payload = JSON.stringify({ amount: AMOUNT });

    const res = http.post(url, payload, buildParams());

    // 커스텀 Trend 기록
    reserveTrend.add(res.timings.duration);

    const ok = check(res, {
        "reserve status is 201": (r) => r.status === 201,
    });

    // 실패 시 디버그 로그
    if (!ok && DEBUG) {
        console.error(`reserve failed: status=${res.status}, body=${res.body}`);
    }

    sleep(PACE_SEC);
}

// ====== options: RUN 값으로 smoke/load 중 하나만 켜기 ======
export const options = (() => {
    const smoke = {
        executor: "ramping-vus",
        startVUs: 1,
        stages: [
            { duration: "5s", target: 1 },
            { duration: "10s", target: 1 },
            { duration: "5s", target: 0 },
        ],
        gracefulRampDown: "10s",
        exec: "runReserve",
    };

    // “진짜 부하”는 VU가 아니라 RPS(도착률) 기준이 더 일관적이라 arrival-rate로 구성
    const load = {
        executor: "ramping-arrival-rate",
        startRate: 0,
        timeUnit: "1s",
        preAllocatedVUs: 50,
        maxVUs: 300,
        stages: [
            { target: 20, duration: "1m" },
            { target: 50, duration: "2m" },
            { target: 100, duration: "3m" },
            { target: 150, duration: "3m" },
            { target: 200, duration: "3m" },
            { target: 0, duration: "1m" },
        ],
        exec: "runReserve",
    };

    return {
        scenarios: RUN === "load" ? { load } : { smoke },

        // endpoint tag 기준으로 threshold 적용(다른 API랑 섞여도 안전)
        thresholds: {
            "http_req_failed{name:prepayment_reserve}": ["rate<0.01"],
            "http_req_duration{name:prepayment_reserve}": ["p(95)<100", "p(99)<300"],
        },
    };
})();

// k6 시나리오에서 호출할 함수
export function runReserve() {
    reserveOnce();
}
