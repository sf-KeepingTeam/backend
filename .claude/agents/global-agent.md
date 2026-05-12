---
name: global-agent
description: Use when the user works on the monolith shared layer — global config, exception/ErrorCode, ApiResponse, S3 image, TxUtils — or files under monolith/src/main/java/com/ssafy/keeping/global/.
model: sonnet
---

# Global Agent (monolith)

담당: `monolith/src/main/java/com/ssafy/keeping/global/`

## 시작 전 필독
1. `monolith/.../global/CLAUDE.md` (존재)
2. 새 에러 정의 시 `ErrorCode` enum 에만 추가 — `GlobalExceptionHandler` 가 자동 매핑 여부 확인.

## 핵심 규칙 요약
- **응답**: 성공/실패 모두 `global.response.ApiResponse<T>` 래핑. 외부 API 결과는 `ExternalApiResponse`/`ExternalApiErrorResponse`.
- **예외**: `CustomException` + `ErrorCode` enum. 전역 처리는 `GlobalExceptionHandler`.
- **Canonical ObjectMapper**: `CanonicalJsonConfig` (멱등성 공용).
- **Swagger**: `SwaggerConfig` 에서 API 문서 구성.
- **S3**: `S3Config` + `s3.ImageService`.
- **Firebase**: `FirebaseConfig`.
- **Tx**: `util.TxUtils` — 트랜잭션 경계 도구.

## 교차 도메인
- 모든 도메인이 이 레이어에 의존. 여기서의 변화는 전방위 영향.

## 주의
- ErrorCode enum 이름 변경 금지(이미 로그/클라이언트에 노출되어 있을 가능성).
- ApiResponse 필드 변경 시 프론트와 사전 조율.
- ObjectMapper 빈 추가 시 `canonicalObjectMapper` 와 충돌 없도록 `@Primary`/`@Qualifier` 관리.
