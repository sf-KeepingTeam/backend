import http from "k6/http";
import { check, sleep } from "k6";
import { Trend, Rate } from "k6/metrics";

// ====== 환경변수 ======
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const STORE_ID = __ENV.STORE_ID || "1";
const CUSTOMER_ID = __ENV.CUSTOMER_ID || "1";
const AMOUNT = Number(__ENV.AMOUNT || 10000);

// Steady RPS & Duration
const RPS = Number(__ENV.RPS || 200);            // 고정 도착률
const DURATION = __ENV.DURATION || "5m";         // 측정 구간(권장 5m)

// Warmup
const WARMUP = (__ENV.WARMUP ?? "30s").trim();   // "0s"면 warmup 비활성화
const WARMUP_RPS = Number(
    __ENV.WARMUP_RPS || Math.max(1, Math.floor(RPS * 0.5))
);

// VU 설정(서버가 느려지면 k6가 더 많은 VU가 필요해짐)
const PREALLOC_VUS = Number(__ENV.PREALLOC_VUS || 50);
const MAX_VUS = Number(__ENV.MAX_VUS || 500);

// 실패 시 바디 로그
const DEBUG = (__ENV.DEBUG || "false").toLowerCase() === "true";

// 커스텀 메트릭
const reserveTrend = new Trend("reserve_duration_ms", true);
const appFail = new Rate("app_fail");

// (arrival-rate에서는 sleep 불필요. 필요하면 0~0.01 정도만)
const PACE_SEC = Number(__ENV.PACE_SEC || 0);

function buildParams() {
    return {
        headers: {
            "Content-Type": "application/json",
            "X-TEST-CUSTOMER-ID": String(CUSTOMER_ID),
            "X-TEST-ROLE": "CUSTOMER",
            "X-REQUEST-ID": `${__VU}-${__ITER}-${Date.now()}`,
        },
        tags: { name: "prepayment_reserve" },
    };
}

function reserveOnce() {
    const url = `${BASE_URL}/api/v1/stores/${STORE_ID}/prepayment/reserve`;
    const payload = JSON.stringify({ amount: AMOUNT });

    const res = http.post(url, payload, buildParams());
    reserveTrend.add(res.timings.duration);

    const ok = check(res, {
        "reserve status is 201": (r) => r.status === 201,
    });

    // 전체 요청 대비 실패율이 되도록 "매 요청마다" 기록
    appFail.add(!ok);

    if (!ok && DEBUG) {
        console.error(`reserve failed: status=${res.status}, body=${res.body}`);
    }

    if (PACE_SEC > 0) sleep(PACE_SEC);
}

function buildScenarios() {
    const scenarios = {};
    const warmupEnabled = !(WARMUP === "0s" || WARMUP === "0" || WARMUP === "");

    if (warmupEnabled) {
        scenarios.warmup = {
            executor: "constant-arrival-rate",
            rate: WARMUP_RPS,          // 이제 WARMUP_RPS가 반영됨
            timeUnit: "1s",
            duration: WARMUP,
            preAllocatedVUs: PREALLOC_VUS,
            maxVUs: MAX_VUS,
            exec: "runReserve",
            tags: { phase: "warmup" },
            startTime: "0s",
            gracefulStop: "30s",
        };
    }

    scenarios.measure = {
        executor: "constant-arrival-rate",
        rate: RPS,
        timeUnit: "1s",
        duration: DURATION,
        preAllocatedVUs: PREALLOC_VUS,
        maxVUs: MAX_VUS,
        exec: "runReserve",
        tags: { phase: "measure" },
        startTime: warmupEnabled ? WARMUP : "0s", // warmup 없으면 바로 시작
        gracefulStop: "30s",
    };

    return scenarios;
}

export const options = {
    scenarios: buildScenarios(),

    thresholds: {
        "http_req_failed{name:prepayment_reserve}": ["rate<0.01"],
        "http_req_duration{name:prepayment_reserve}": ["p(95)<100", "p(99)<300"],
        "app_fail": ["rate<0.001"], // 이제 "전체 요청 대비" 0.1% 미만으로 해석 가능
    },
};

export function runReserve() {
    reserveOnce();
}
