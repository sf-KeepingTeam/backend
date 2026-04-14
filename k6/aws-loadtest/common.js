import http from 'k6/http';

// Nginx Public IP를 BASE_URL로 사용
export const BASE_URL = __ENV.BASE_URL || 'http://43.203.181.93';

// LoadTest 헤더 기반 인증
export function getTestHeaders(customerId, role = 'CUSTOMER') {
    return {
        'Content-Type': 'application/json',
        'X-Test-User-Id': String(customerId),
        'X-Test-User-Role': role,  // QR Service용
        'X-Test-Role': role         // Monolith용
    };
}

// 응답 시간 임계값 (ms)
export const THRESHOLDS = {
    p95: 500,
    p99: 1000,
};
