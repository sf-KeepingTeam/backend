---
description: 2차 코드 리팩토링. 1차 정비(cleanup) 이후 실행. 도메인 CLAUDE.md를 먼저 읽고 컨벤션에 맞게 코드 품질을 개선. 특정 도메인이나 패턴을 인자로 받을 수 있음.
---

# 2차 리팩토링 (Refactor)

1차 정비(cleanup) 이후에 실행한다. 기능은 그대로 유지하면서 코드 품질과 가독성을 높인다.

## 시작 전 필수 확인

리팩토링할 도메인의 CLAUDE.md를 먼저 읽는다:

```
$ARGUMENTS 가 주어지면 해당 도메인 우선, 없으면 아래 우선순위 순서로
```

우선순위: `payment` → `qr` → `wallet` → `idempotency` → 나머지

```bash
# 해당 도메인 CLAUDE.md 읽기
cat monolith/src/main/java/com/ssafy/keeping/domain/{도메인}/CLAUDE.md
cat qr-service/src/main/java/com/ssafy/keeping/qr/domain/{도메인}/CLAUDE.md
```

## 리팩토링 체크리스트

### A. 상태 머신 / 비즈니스 규칙 강화

- 상태 전이 메서드가 엔티티 내부에 있는가? (서비스에서 직접 `.setStatus()` 하면 안 됨)
- `CustomException(ErrorCode.XXX)` 형식 통일 확인
- `ErrorCode` enum에 없는 에러 메시지가 서비스 코드에 하드코딩되어 있으면 enum으로 이동

### B. 트랜잭션 경계 검토

- `@Transactional` 범위가 외부 API 호출을 포함하지 않는지 확인
- 특히 `WalletClient.capture` 호출 전후 트랜잭션 경계 주의
- `Propagation.REQUIRES_NEW` 사용처 주석 보강

### C. 의존성 / 레이어 정리

```
Controller → Service → Repository (역방향 금지)
ACL Client → (외부 서비스) (도메인 모델에 ACL DTO 직접 사용 금지)
```

서비스가 다른 서비스를 직접 의존하면 순환 의존 위험 — 확인 후 인터페이스 분리 고려

### D. 로깅 정리

- DEBUG/INFO/WARN/ERROR 레벨이 적절한지 확인
- 민감 정보(PIN, JWT 일부) 로그 출력 여부 확인
- 복구 경로(`PaymentRecoveryService`)의 ERROR 로그가 충분한지 확인

### E. 코드 중복 제거

```bash
# 유사한 패턴 찾기
grep -rn "X-Internal-Auth\|createHeaders" --include="*.java" qr-service/src/main
```

공통 유틸로 추출할 수 있는 패턴 식별

## 리팩토링 단위

한 번에 한 파일 또는 한 패턴만 변경하고 컴파일 체크:

```bash
# 변경 후 즉시 확인
(cd monolith && ./gradlew compileJava --daemon -q) || echo "❌ 컴파일 실패"
(cd qr-service && ./gradlew compileJava --daemon -q) || echo "❌ 컴파일 실패"
```

## 커밋 컨벤션

```
refactor(도메인): 변경 내용 한 줄 요약

- 구체적 변경 사항 1
- 구체적 변경 사항 2
```

예시:
```
refactor(payment): extract state transition to entity methods

- Move markApproved/markDeclined validation into PaymentIntent entity
- Remove direct status field access from PaymentService
```

## 완료 기준

- `./gradlew compileJava` 양 모듈 통과
- 변경 전후 동작이 동일함을 주석/로그로 확인
- 각 변경이 독립적인 커밋으로 분리됨
