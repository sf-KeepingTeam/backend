---
description: 1차 코드 정비. 죽은 코드 탐색, 미사용 import 제거, TODO 목록화, 빌드 검증까지 체계적으로 수행. 리팩토링 전 코드베이스를 정리할 때 사용.
---

# 1차 코드 정비 (Cleanup)

KEEPING 백엔드의 1차 정비를 체계적으로 수행한다. 목표는 **기능 변경 없이** 코드를 깔끔하게 만드는 것이다.

## 정비 순서

### Step 1 — 죽은 코드 탐색

각 모듈에서 아래를 찾아 목록을 만든다:

```bash
# 미사용 public 메서드/클래스 단서 찾기
grep -r "TODO\|FIXME\|HACK\|XXX" --include="*.java" monolith/src qr-service/src

# 테스트 없는 서비스 클래스 확인
find monolith/src/test qr-service/src/test -name "*Test*.java" | sort
```

발견한 항목을 카테고리별로 정리한다:
- **즉시 삭제 가능**: 완전히 미참조 코드
- **주석 처리 후 삭제**: 히스토리 파악 후 제거
- **TODO → 이슈화**: 삭제하지 않고 문서화

### Step 2 — 미사용 import 제거

```bash
# 미사용 import 패턴 찾기
grep -rn "^import " --include="*.java" monolith/src/main | grep -v "//\|/\*"
```

IDE 없이 작업 시: 파일별로 직접 확인하고 `./gradlew compileJava`로 검증.

### Step 3 — 설정/환경변수 정리

- `.env.example` 누락 항목 확인 (`INTERNAL_AUTH_TOKEN` 등)
- `application-{profile}.yml` 중복 설정 확인
- 하드코딩된 값 식별 (Kakao OAuth client-id 등)

```bash
# 하드코딩 의심 값 탐색
grep -rn "http://\|localhost\|127.0.0.1\|password\|secret" \
  --include="*.java" --include="*.yml" \
  monolith/src/main qr-service/src/main | grep -v "test\|Test\|#"
```

### Step 4 — 빌드 검증

정비 완료 후 두 모듈 모두 클린 빌드 확인:

```bash
(cd monolith && ./gradlew clean compileJava -x test --daemon)
(cd qr-service && ./gradlew clean compileJava -x test --daemon)
```

### Step 5 — Git 커밋

각 카테고리별로 분리 커밋:
```
git add -p  # 변경 사항 검토 후 선택적 스테이징
git commit -m "chore: remove unused imports and dead code"
git commit -m "chore: clean up TODO comments and add inline docs"
```

## 건드리지 말아야 할 것

- `PaymentRecoveryService`, `IntentStatusUpdater` — 복구 로직은 함부로 건드리지 말 것
- `IdempotencyService` — 멱등성 로직, 변경 시 반드시 테스트
- `QrTokenService` — TTL 값 변경 시 QR 흐름 전체 영향
- `WalletClient` — write/read/recovery RestTemplate 분리 유지

## 출력 형식

정비 결과를 아래 형식으로 요약한다:

```
## 1차 정비 결과

### 삭제한 것
- [파일명] [내용] — [이유]

### 보류한 것 (이슈화 필요)
- [파일명] [내용] — [이유]

### 발견한 개선 포인트 (2차 정비 대상)
- [내용]

### 빌드 상태
- monolith: ✅ / ❌
- qr-service: ✅ / ❌
```
