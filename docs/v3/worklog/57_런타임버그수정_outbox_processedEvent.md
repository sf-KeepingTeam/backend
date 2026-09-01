# 57 — 런타임 버그 수정: OutboxPublisher self-invocation (Bug A) + ProcessedEvent merge→persist (Bug B)

날짜: 2026-08-24  
브랜치: infra/loadtest-setup  

---

## 배경

배포 후 프로덕션 지표에서 두 버그 발견.

| 지표 | 관측값 | 의미 |
|---|---|---|
| `payment_outbox` PENDING 행 수 | 125 (전부) | 발행 후 SENT 전이 없음 |
| `payment_outbox_published_total` | 327,557 | 같은 행을 반복 시도 |
| Kafka topic offset | ~333K | 폴러가 계속 발행 중 |
| consumer lag | 98,103 | 컨슈머가 따라가지 못함 |
| `consumed_total{SUCCESS}` | 233,684 | 125 이벤트 → 23만 알림 |
| `duplicate_skipped_total` | 0 | 멱등성 체크 전혀 작동 안 함 |

---

## Bug A — OutboxPublisher self-invocation

### 근본 원인

`OutboxPublisher.publishPending()` (@Scheduled) 가 `this.fetchPending()`, `this.markSent()`, `this.markRetry()`, `this.markFailed()` 를 호출. 이 메서드들에는 `@Transactional` 이 붙어 있지만, **`this.*` 직접 호출은 Spring AOP 프록시를 우회**하므로 트랜잭션이 열리지 않는다.

- 트랜잭션 없음 → Hibernate dirty-check 비활성 → `markSent()` 내부의 `o.markSent(...)` 호출이 엔티티 필드를 바꿔도 UPDATE SQL 미발행
- PENDING 행이 영원히 PENDING → 폴러가 같은 125행을 500ms마다 재발행 → 327K 중복 발행

`cleanup()` 의 `this.deleteOldSentBatch()` 도 동일 이유로 `EntityManager.createNativeQuery().executeUpdate()` 가 트랜잭션 없는 상태에서 실행되어 항상 실패했을 것.

### 전수 조사 결과

`@Scheduled` 메서드에서 `this.*` 내부 `@Transactional` 호출 패턴을 전 코드베이스 대상으로 확인:

| 파일 | 패턴 | 판정 |
|---|---|---|
| `OutboxPublisher` | `@Scheduled` → `this.fetchPending/markSent/markRetry/markFailed/deleteOldSentBatch` | **버그 (수정 대상)** |
| `PaymentRecoveryService` | `@Scheduled` → `transactionTemplate.executeWithoutResult()` | 정상 (TransactionTemplate 직접 사용) |
| `IdempotencyService` | `@Scheduled @Transactional cleanupStalledInProgress()` 내 `repository.*` 직접 호출 | 정상 (self-invocation 없음) |
| `WalletReconciliationScheduler` | `@Scheduled` → `reconciliationService.runOnce()` (별도 @Service) | 정상 |
| `LotExpiryScheduler` | `@Scheduled` → `lotExpiryService.sweepOnce()` (별도 @Service) | 정상 |
| `PaymentReservationScheduler` | `@Scheduled @Transactional` 직접 + repository 호출 | 정상 |
| `SettlementScheduler` | `// @Service` 주석처리 — 비활성 | 해당 없음 |

**OutboxPublisher 단독 위반.**

### 수정 방법 선택

| 옵션 | 장 | 단 |
|---|---|---|
| Self-injection (`@Lazy @Autowired OutboxPublisher self`) | 코드 변경 최소 | 순환 의존 냄새, 테스트 복잡 |
| 별도 `@Component OutboxTransactionHelper` 추출 | 관심사 분리 | 불필요한 신규 클래스 |
| **`@Modifying @Query` 리포지토리 메서드** (채택) | 프록시 항상 통과, 엔티티 로드 없는 단건 UPDATE, 신규 클래스 없음 | — |

### 변경 파일

**`PaymentOutboxRepository`** — 4개 `@Transactional @Modifying @Query` 추가:
- `markSent(id, sentAt)` — JPQL UPDATE, status=SENT, sentAt 갱신
- `incrementRetry(id, error)` — retryCount+1, lastError 갱신
- `markFailed(id, error)` — status=FAILED, lastError 갱신  
- `deleteOldSentBatch(cutoff, batchSize)` — native DELETE…LIMIT (JPQL 은 DELETE LIMIT 미지원)
- 기존 `deleteByStatusAndSentAtBefore` 에 누락된 `@Transactional` 추가

**`OutboxPublisher`** — self-invocation 전부 제거:
- `@PersistenceContext EntityManager` 필드 제거
- `fetchPending()`, `markSent()`, `markRetry()`, `markFailed()`, `deleteOldSentBatch()` 메서드 제거
- `publishPending()` 에서 `repository.findByStatusOrderByIdAsc()` 직접 호출, PendingEntry 스칼라 변환 인라인
- Kafka send 성공 시 `repository.markSent(id, now)` 호출
- `handleFailure()` 에서 `repository.incrementRetry()` / `repository.markFailed()` 호출
- `cleanup()` 에서 `repository.deleteOldSentBatch(cutoff, CLEANUP_BATCH_SIZE)` 호출

---

## Bug B — ProcessedEvent merge→persist

### 근본 원인

```java
@Id
@Column(name = "event_id", length = 36, nullable = false)
private String eventId; // @GeneratedValue 없음
```

`SimpleJpaRepository.save()` 는 `isNew()` 가 true 일 때만 `entityManager.persist()` 를 호출한다.  
`isNew()` 의 기본 구현: `@GeneratedValue` 가 없으면 ID 가 non-null 일 때 `false` 반환 → `merge()` 호출.  
`merge()` 는 DB에 이미 없으면 INSERT, 있으면 UPDATE — UNIQUE 제약 위반이 발생하지 않는다.  
결과: `DataIntegrityViolationException` 이 절대 발생하지 않아 `duplicate_skipped_total` 이 항상 0.

### 수정 방법 선택

| 옵션 | 판정 |
|---|---|
| `existsById` 사전 조회 | TOCTOU 경쟁 조건 — 두 스레드가 동시에 false 받으면 둘 다 save 진행 |
| `INSERT IGNORE` / `ON DUPLICATE KEY IGNORE` | MySQL 전용, DB 중립성 훼손 |
| **`Persistable<String>` 구현** (채택) | JPA 표준, DB 중립, 경쟁 조건 없음 |

### 변경 파일

**`ProcessedEvent`**:
- `@AllArgsConstructor` 제거 → 명시적 3-arg 생성자로 대체 (`isNew` 필드는 기본값 true 유지)
- `Persistable<String>` 구현
- `@Transient boolean isNew = true` — 새 인스턴스는 항상 persist 경로
- `@PostLoad void markNotNew()` — DB에서 로드된 후 false 전환 (merge 경로)
- `getId()`, `isNew()` override

이제 `processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, eventType, now))` 에서  
`isNew()` == true → `persist()` → UNIQUE 위반 시 `DataIntegrityViolationException` → 컨슈머가 catch → 중복 skip 정상 동작.

---

## 인프라 3종 버그 사후 예방

이전 커밋(53f2839, 0bb6ac5)에서 수동 수정한 버그:
1. DDL 타입 불일치 (`event_id VARCHAR(36)`, `DATETIME(6)`)
2. `@EnableKafka` 누락으로 컨슈머 미기동
3. `JpaConfig` `basePackages` 에 outbox 패키지 누락

공통 원인: **정적 분석이 런타임 컨텍스트 기동을 대체할 수 없다.**

예방책: `@SpringBootTest` + `ddl-auto=validate` 컨텍스트 스모크 테스트를 CI 에 추가.  
이 테스트 하나가 위 세 버그를 모두 기동 시점에 잡는다:
- DDL vs 엔티티 타입 불일치 → `SchemaManagementException`
- `@EnableKafka` 누락 → `NoSuchBeanDefinitionException` (KafkaListenerContainerFactory)
- JPA `basePackages` 누락 → `EntityNotFoundException` 또는 `@Repository` 미등록

---

## 완료 조건

- [x] `PaymentOutboxRepository` — 4개 `@Modifying` 메서드 추가, 기존 delete에 `@Transactional` 추가
- [x] `OutboxPublisher` — self-invocation 완전 제거, EntityManager 의존 제거
- [x] `ProcessedEvent` — `Persistable<String>` 구현, `@AllArgsConstructor` 제거
- [x] `docs/failures.md` — Bug A, Bug B 기록
- [x] `docs/decisions.md` — 수정 방법 선택 근거 기록
