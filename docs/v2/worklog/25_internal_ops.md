# 세트 #25 — /internal/ops/* 운영 컨트롤러

> 오케스트레이션AI가 직접 수행.

## 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `domain/internal/controller/InternalOpsController.java` | [신규] | 만료 스윕 + 대사 즉시 실행 엔드포인트 |

## 엔드포인트

| 메서드 | 경로 | 인증 | 반환 |
|---|---|---|---|
| POST | `/internal/ops/lot-expiry/run` | `X-Internal-Auth` | `ApiResponse<ExpirySweepReport>` |
| POST | `/internal/ops/reconcile/run` | `X-Internal-Auth` | `ApiResponse<ReconcileReport>` |

## 계약 준수

- C-9: 경로·인증·반환 타입 계약 그대로
- `validateInternalAuth` 호출: 두 메서드 모두 첫 줄에서 호출
- 컨트롤러는 위임만. 비즈니스 로직 없음
- domain/wallet, domain/payment 수정: 없음
