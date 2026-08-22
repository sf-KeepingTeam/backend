# PR #22: restore 엔드포인트/서비스 제거

## 변경 요약

`InternalWalletController`의 `POST /{walletId}/stores/{storeId}/restore` 엔드포인트와 `InternalWalletService.restore()` 메서드를 제거.

## 제거 근거

1. **호출자 없음**: qr-service 측에서도 #21에서 제거 완료. grep으로 `.restore(` 참조가 controller 내부 호출 1건(제거 대상) 외에 없음을 확인.
2. **기능 중복**: `processRefund`가 동일 역할을 멱등성 보장 + lot 복원과 함께 수행.
3. **원장 파괴 위험**: lot 근거 없이 balance만 올리는 도구 — lot-balance 불일치의 원인.

## 삭제 항목

- `InternalWalletService.restore(Long walletId, Long storeId, Long amount)` 메서드 (35행)
- `InternalWalletController`의 restore 엔드포인트 메서드 + `RestoreRequest` 내부 record

## 테스트

- `InternalWalletServiceTest` (D-1, D-2)
  - D-1: `InternalWalletService`에 restore 메서드 미존재 확인 (reflection)
  - D-2: `InternalWalletController`에 RestoreRequest 내부 클래스 미존재 확인
