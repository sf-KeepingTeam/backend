// API 기본 URL
const API_BASE_URL = 'http://localhost:8080';

// 테스트용 JWT 토큰 (JwtTokenGenerator로 생성 필요)
// TODO: 실제 토큰으로 교체
const TEST_JWT_TOKEN = 'eyJhbGciOiJIUzM4NCJ9...';

// 토스페이먼츠 클라이언트 키
const TOSS_CLIENT_KEY = 'test_ck_0RnYX2w532M1AJByZZDx8NeyqApQ';

// API 요청 헤더
const API_HEADERS = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${TEST_JWT_TOKEN}`
};
