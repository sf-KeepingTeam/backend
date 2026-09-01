# PR #17 — Platform Agent 워크로그

## 작업 요약

v2 지갑 원장(Wallet-Ledger) 기능을 위한 **공통 플랫폼 토대**를 구축했다. 기능 코드는 0이며, 도메인 에이전트가 바로 사용할 수 있는 인프라만 배치.

## 변경 파일

### 신규 생성

| 파일 | 설명 |
|---|---|
| `global/metrics/LedgerMetrics.java` | 지갑 원장 Prometheus 메트릭 7종 (Counter 4 + Gauge 3). Gauge는 AtomicLong 강한 참조로 GC/NaN 방지 |
| `global/config/WalletLedgerProperties.java` | `@ConfigurationProperties(prefix = "wallet")`. 만료 배치, 캡처 모드, 대사 스케줄 설정 |
| `global/config/ShedLockConfig.java` | ShedLock 분산 스케줄러 락. Redis 기반, 네임스페이스 `monolith-ledger`, 기본 최대 락 PT30M |
| `test/.../global/metrics/LedgerMetricsTest.java` | LedgerMetrics 단위 테스트 — 7개 메트릭 등록 + Gauge 값 갱신 검증 |
| `test/.../global/config/WalletLedgerPropertiesTest.java` | WalletLedgerProperties 바인딩 + 기본값 테스트 |

### 수정

| 파일 | 변경 내용 |
|---|---|
| `monolith/build.gradle` | `shedlock-spring:5.16.0`, `shedlock-provider-redis-spring:5.16.0` 의존성 추가 |
| `application.yml` | `wallet:` 블록 추가 (expiry/capture/reconcile 기본값) |
| `ErrorCode.java` | `REFUND_ORIGINAL_TX_REQUIRED(BAD_REQUEST, "환불 요청에 원거래 ID가 필요합니다.")` 추가 |
| `global/CLAUDE.md` | 구조도, 핵심 클래스 테이블, 주의사항에 신규 3개 클래스 반영 |

## 메트릭 목록

| 이름 | 타입 | 태그 | 헬퍼 메서드 |
|---|---|---|---|
| `wallet_ledger_mismatch_total` | Counter | `cause` | `mismatch(String cause)` |
| `wallet_lot_expired_total` | Counter | — | `lotExpired(long lots, long amount)` |
| `wallet_lot_expired_amount_total` | Counter | — | (위와 동일 메서드) |
| `wallet_lot_expiry_backlog` | Gauge | — | `expiryBacklog(long remaining)` |
| `wallet_lot_expiry_skipped_locked_total` | Counter | — | `expirySkippedLocked(long n)` |
| `wallet_reconcile_mismatch_pairs` | Gauge | — | `reconcileResult(long, Instant)` |
| `wallet_reconcile_last_run_epoch_seconds` | Gauge | — | (위와 동일 메서드) |

## WalletLedgerProperties 기본값

```yaml
wallet:
  expiry:
    lazy-enabled: true
    batch-size: 500
    max-groups-per-run: 200
    cron: "0 10 3 * * *"
  capture:
    strict-lot: false
  reconcile:
    cron: "0 40 3 * * *"
    page-size: 1000
```

## 주의사항

- Gauge의 AtomicLong은 빈 필드로 강하게 보관. 약한 참조 함정 방지.
- ShedLock 네임스페이스 `monolith-ledger`는 qr-service의 `qr-recovery`와 완전 분리.
- 테스트는 작성만 했고 실행하지 않음 (실행 제약).
