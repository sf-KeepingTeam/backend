# 인프라 담당 인계 — v3 개선 검증 및 재측정

너는 **인프라·배포·부하측정 담당**이다.
**코드 로직은 건드리지 마라. 설정·환경변수·인프라·측정 도구(k6)만.**
계측을 위해 애플리케이션 코드가 꼭 필요하면 먼저 사람에게 물어라.

---

## 0. 지금 상황 — 세 줄

1. **v2 에서 부하측정을 마쳤다.** 결제 지연의 원인 3가지를 특정했다 → `docs/측정결과_부하격리/result.md`
2. **v3 에서 코드팀이 그 3가지를 고쳤다.** → `docs/v3/worklog/`
3. **그런데 그 코드는 한 번도 컴파일된 적이 없다.** 사람이 자는 동안 자동 승인으로 진행돼서,
   **빌드·테스트·실행을 전부 금지한 채 코드만 작성**했다.

**네 첫 임무는 "돌아가는지 확인"이고, 그다음이 "before/after 측정"이다.**

---

## 1. ★ 아직 검증되지 않은 것 (전부 네 몫)

| # | 미검증 항목 | 위험 |
|---|---|---|
| 1 | **컴파일** (monolith · qr-service) | 신규 파일 16개 + 수정 9개. **컴파일 실패 가능성이 실재한다** |
| 2 | **단위 테스트 실행** | 새로 쓴 테스트 6개가 한 번도 안 돌았다 |
| 3 | **실제 동작 (스모크)** | 결제 4단계가 되는지 확인 안 됨 |
| 4 | **D-1 수정본 검증** | `fallbackExecution=true` 로 고쳤으나 미실행. **틀리면 승인 알림 100% 유실** |
| 5 | **NTP 동기화** | monolith/qr 시계가 30초 이상 어긋나면 PIN 토큰 `exp`·jti TTL 오동작 |
| 6 | **새 엔드포인트 보안** | `POST /customers/pin/verify-token` (monolith:8080) 가 SecurityConfig·백도어 필터와 맞물리는지 |
| 7 | **k6 스크립트** | **`ApproveRequest` 에 `pinToken` 필드가 생겼는데 스크립트는 `pin` 만 보낸다** → 아래 §3 |

컴파일 실패 시 코드팀이 지목한 의심 지점:
- monolith `PinTokenService` → `JwtProperties.secret()` 접근자 이름
- qr-service `PaymentIntentService` 생성자 파라미터 16개 순서
- qr-service `ApproveTransactionHelper` → `IdempotencyKeyRepository` import

**컴파일 에러가 나면 고치지 말고 코드팀에 넘겨라.** 네 범위 밖이다.

---

## 2. 실행 순서

```
[1] 컴파일 확인        → 실패하면 여기서 멈추고 코드팀에 보고
[2] 단위 테스트 실행   → 실패 목록을 코드팀에 보고
[3] k6 스크립트 수정   → PIN 토큰 경로 (§3)
[4] 배포              → ★ monolith 먼저, 그다음 qr-service (§4)
[5] 스모크            → 플래그 조합별로 결제 4단계 확인
[6] 측정              → 플래그 한 번에 하나씩 (§5)
```

```bash
(cd monolith   && ./gradlew clean compileJava -x test)
(cd qr-service && ./gradlew clean compileJava -x test)
(cd monolith   && ./gradlew test)     # Testcontainers → Docker 필요
(cd qr-service && ./gradlew test)
```

---

## 3. ★ k6 스크립트 수정이 필요하다 (네 영역)

`qr-service` 의 `ApproveRequest` 에 **`pinToken` 필드가 추가**됐다.

```java
public class ApproveRequest {
  private String pin;
  private String pinToken;   // ← 신규
}
```

현재 `k6/performance-comparison/02-qr-payment-flow.js` 는 `pin` 만 보낸다.
**그대로 재면 `pin.token-enabled=true` 여도 토큰 경로를 타지 않는다.** 측정이 무의미해진다.

### 추가할 단계

```
3) POST /cpqr/{sessionToken}/initiate            → intentId
3.5) POST http://{MONOLITH}:8080/customers/pin/verify-token   ← ★ 신규
       { pin, intentPublicId }  →  pinToken
4) POST /payments/{intentId}/approve             { pinToken }
```

- 정확한 요청/응답 스키마는 `monolith/.../PinTokenController.java` 와
  `docs/v3/worklog/32_PIN토큰발급.md` 를 읽고 맞춰라
- **`QR_MODE`(constant/ramp) 처럼 환경변수로 켜고 끌 수 있게** 만들어라
  (`PIN_MODE=token|pin`). 플래그 조합별 측정에 필요하다

### ⚠️ 그리고 이걸 반드시 함께 관찰해라 — 개선인가 이동인가

토큰이 **intent 단위로 발급**되면 결제 1건당 Argon2 계산 횟수는 **줄지 않는다.**
`approve()` 안에서 하던 것을 **클라이언트가 미리 하는 것**으로 위치만 바뀐다.

| | 기대 |
|---|---|
| `approve` p95 | **크게 개선** (monolith 왕복 1회 제거, 로컬 JWT 검증 ~2ms) |
| **전체 플로우 p95** | **덜 개선.** 토큰 발급 호출이 새로 붙는다 |
| 처리량 | **개선** (qr 스레드·커넥션이 덜 묶인다) |

**`approve` 만 좋아지고 전체 플로우가 그대로면 그건 개선이 아니라 이동이다.**
반드시 둘 다 적어라. 처리량(초당 건수)이 진짜 판정 기준이다.

---

## 4. 배포 제약

```
monolith 배포 → 확인 → qr-service 배포
```

qr-service 의 PIN 토큰 검증은 monolith 가 발급한 토큰을 소비한다. 역순이면 발급처가 없다.

**JWT 시크릿은 이미 공유돼 있다** — 양쪽 다 `${JWT_SECRET:...}` 로 읽고 `.env` 에 같은 값이 들어 있다.
확인만 하고 넘어가라.

---

## 5. 측정 계획 — 플래그를 한 번에 하나씩

새 설정 키 3개(전부 qr-service, 기본값 `true`):

| 환경변수 | 의미 |
|---|---|
| `PAYMENT_NOTIFICATION_ASYNC` | 알림을 커밋 후 비동기 발송 |
| `PAYMENT_APPROVE_SPLIT_TRANSACTION` | approve 를 TX-A / NO-TX / TX-B 로 분리 |
| `PAYMENT_PIN_TOKEN_ENABLED` | PIN 토큰 로컬 검증 (monolith 왕복 제거) |

### ★ 라벨 주의 — 인계 문서의 A0~B0 를 그대로 쓰지 마라

`docs/v3/worklog/인프라_인계.md` §3 이 조합을 `A0~A3/B0` 로 불렀는데,
**그 라벨은 v2 측정에서 이미 다른 뜻으로 쓰였다**(A0=구성1 무부하, B0=구성2 무부하 …).
`~/results/` 와 `result.md` 가 통째로 오염된다. **`F0~F4` 로 바꿔 쓴다.**

| 라벨 | async | split-tx | token | 목적 |
|---|---|---|---|---|
| **F0** | false | false | false | **개선 전 재현.** B1 과 같아야 한다 = 회귀 없음 확인 |
| **F1** | **true** | false | false | 알림 비동기 단독 효과 |
| **F2** | false | **true** | false | 트랜잭션 분리 단독 효과 |
| **F3** | false | false | **true** | PIN 토큰 단독 효과 |
| **F4** | **true** | **true** | **true** | 전체 (운영 기본값) |

**F4 를 먼저 재지 마라.** 개별 효과를 분리할 수 없게 된다.

### 러닝 명령 (구성 2 · B1 과 완전히 같은 조건)

```bash
cd ~/keeping/k6
RUN_MODE=legacy ./runner.sh F0 config2 500 30 4m "flags: 전부 false — B1 재현"
RUN_MODE=legacy ./runner.sh F1 config2 500 30 4m "flags: async만"
RUN_MODE=legacy ./runner.sh F2 config2 500 30 4m "flags: split-tx만"
RUN_MODE=legacy ./runner.sh F3 config2 500 30 4m "flags: token만"
RUN_MODE=legacy ./runner.sh F4 config2 500 30 4m "flags: 전부 true"

# 수용량 (constant 모드) — S2-60 과 비교
RUN_MODE=v2 ./runner.sh F4-cap config2 0 60 3m "flags: 전부 true, 수용량"
```

**구성 2 는 냉동 상태로 재면 안 된다.** 매번 배포 후 워밍업을 돌려라
(v2 에서 이걸 한쪽에만 적용해 A0↔B0 비교가 편향됐다 — `result.md` §3-7-5).

```bash
RUN_MODE=legacy ./runner.sh WARM config2 200 10 3m "워밍업 — 폐기"
```

---

## 6. before 기준선 — 이것과 비교한다

| | **B1** (배경 500 / 결제 30, legacy) | **S2-60** (무부하 60 VU, constant) |
|---|---|---|
| 성공률 | 99.1% | 100% |
| **처리량** | **4.03 건/초** | **22.30 건/초** |
| approve p95 | 6,686.6 ms | 1,148.7 ms |
| Intent p95 | 2,881.4 ms | 568.7 ms |
| 전체 플로우 p95 | 9,629.4 ms | 2,080.4 ms |
| qr Hikari active 최대 | **15 (포화)** | — |
| qr Hikari pending 최대 | **12** | — |
| **`/actuator/health`** | **424 ms** | — |
| PIN 검증 (monolith 서버측) | 627.3 ms | — |
| 알림 (monolith 서버측) | 196.7 ms | — |

원본: `docs/측정결과_부하격리/raw/B1/`, `raw/S2-60/`
(`meta.txt` 조건 · `metrics.csv` 5초 시계열 · `volume.txt` 실투입량)

### 항목별로 봐야 할 것

| 개선 | 확인할 지표 | 기대 |
|---|---|---|
| 알림 비동기 (F1) | approve 에서 알림 구간 소멸 / monolith 쓰기 감소 / `[NOTIFICATION_DROPPED]` 빈도 | 196.7 ms 제거 |
| **TX 분리 (F2)** | **`/actuator/health`** ← 가장 명확<br>`hikaricp_connections_active{qr}` 최대, `pending` | **424 ms → 수십 ms**<br>15 → 15 미만, 12 → 0 |
| PIN 토큰 (F3) | `[APPROVE_PHASE] phase=PIN_TOKEN elapsedMs=` | 627 ms → ~2 ms |

새로 생긴 로그 관측 지점은 `docs/v3/worklog/인프라_인계.md` §5 에 전부 있다
(`[APPROVE_PHASE]` · `[APPROVE_ORPHAN]` · `[NOTIFICATION_DROPPED]` · `[PIN_TOKEN]`).

---

## 7. 함정 목록 (v2 에서 실제로 겪은 것)

| | |
|---|---|
| **러너가 대상 생존을 확인한다** | 사전 실패 시 `exit 2`. 대상이 죽었으면 살리고 다시 실행하라. **무시하고 강행하지 마라** |
| **호스트가 두 번 멎었다** | payment(t3.small→medium), main(결제 150 VU). SSH 가 멈추면 콘솔에서 재부팅 |
| **k6 는 닫힌 모델이다** | VU 수는 RPS 가 아니다. 서버가 느려지면 투입량도 줄어든다. **`volume.txt` 의 실측 RPS 를 적어라** |
| **램프 vs constant** | `RUN_MODE=legacy` 는 ramp(1차 재현용), 기본 `v2` 는 constant(절대값 주장용). **섞어서 비교하지 마라** |
| **Windows git 이 실행 비트를 지운다** | EC2 에서 `chmod +x` 하면 다음 `git pull` 이 충돌한다. `git checkout -- <파일>` 후 pull |
| **git 은 사람이 PowerShell 에서 실행** | 마운트가 `unlink` 를 막아 `.git/index.lock` 이 남는다 |
| **`docs/**/*.md` 는 gitignore 대상** | 의도된 정책이다. 문서는 로컬 파일이 원본. 커밋 로그에서 찾지 마라 |

---

## 8. 측정이 끝나면 (아직 하지 마라)

- [ ] `LOADTEST_BACKDOOR_ENABLED=false` (allinone / main / payment 3대)
- [ ] `SPRING_PROFILES_ACTIVE` 에서 `loadtest` 제거
- [ ] 보안그룹에서 8080/8081 의 `keeping-loadgen-sg` 규칙 삭제
- [ ] 측정 전용 시크릿 폐기 (`JWT_SECRET` / `INTERNAL_AUTH_TOKEN` / `MYSQL_ROOT_PASSWORD`)
- [ ] `~/results/` 를 로컬로 회수 (**loadgen 을 손대기 전에**)
- [ ] EC2 정지

그다음이 **Kafka** 다. `keeping-allinone` 에 올린다 (구성 1 은 측정이 끝나 역할이 없다).
`keeping-loadgen` 은 건드리지 마라 — 재측정 능력을 잃는다.

---

## 9. 결과 기록

**`docs/측정결과_부하격리/result.md` 에 이어서 적는다.** 새 파일 만들지 마라.

- 러닝마다 조건·실투입량·서버지표를 전부 남긴다 (러너가 `meta.txt`·`volume.txt`·`metrics.csv` 로 자동 저장)
- **실패하거나 무효인 러닝도 지우지 말고 사유와 함께 남긴다** (v2 에 그렇게 했다)
- 개선 효과를 과장하지 마라. **"approve 만 빨라지고 전체는 그대로"면 그렇게 적는다**

## 10. 읽을 문서

| | |
|---|---|
| `docs/v3/README.md` | v3 전체 계획 |
| `docs/v3/worklog/인프라_인계.md` | **코드팀이 남긴 인계. 제일 먼저 읽어라** |
| `docs/v3/worklog/34_검증.md` | 정적 검증 보고서 + 알려진 결함 |
| `docs/측정결과_부하격리/result.md` | v2 측정 전체 (§3-9 측정 결함 이력 포함) |
| `docs/v2/infra/` | 인프라 설계 근거 9종 |
