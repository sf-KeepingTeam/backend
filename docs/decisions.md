# 의사결정 기록 (인덱스)

<!-- 규칙: 한 결정 = 한 줄. 상세 배경이 필요하면 docs/decisions/ 아래 별도 파일로 빼고 링크.
     기록 기준: "이걸 모르면 다음 세션의 AI가 다르게 행동하는가?" — 아니면 기록하지 않는다.
     낡은 결정은 주기적으로 삭제한다. -->

| 날짜 | 결정 | 이유 |
|---|---|---|
| 2026-08-21 | LOT 만료 표현: `LotStatus.EXPIRED` 대신 컬럼 2개(`expired_settled_at`, `expired_amount`) + `amount_remaining=0` | DDL CHECK 제약 재작성 회피 + GROUP 지갑에 `customer_id` 없음 (ADR-003) |
| 2026-08-21 | `lotLeft != 0` 3단계 전환 (즉시 롤백 대신 관측→실측→플래그) | 어긋난 고객 전원 결제 차단 방지. 발생원을 먼저 막고 실측 0 후 전환 |
| 2026-08-21 | `wallet_store_balances` 유지 (제거 안 함) | balance 행이 결제의 직렬화 앵커(단일행 비관락). 제거 시 결제 락 구조 재설계 필요 |
| 2026-08-23 | 결제 실패 상태 전이(DECLINED/EXPIRED)는 `REQUIRES_NEW` 헬퍼(`finalizeDeclined`/`finalizeExpired`)에서만 하고, 예외는 커밋 후 호출자가 던진다 | 같은 트랜잭션에서 전이+throw 하면 전이까지 롤백된다. `prepareApproval` 은 self-invocation 이라 플래그(`ApprovePhaseAResult.expired`)로 호출자에 위임 |
| 2026-08-23 | `PaymentIntentRepository.expirePendingIntents` 를 스케줄러에 연결하지 않음 | `findRecoveryTargets` 가 `PENDING AND expires_at < now` 를 복구 대상으로 쓴다. 배치로 EXPIRED 를 찍으면 자금 캡처 확인 없이 복구 대상에서 사라짐 |
| 2026-08-23 | 두 서비스의 Redis 공유 여부는 환경변수 문자열 비교가 아니라 SET/GET 프로브로 확인 | 2-EC2 배포에서 양쪽 값이 모두 `redis` 라 항상 "같다"로 오탐. 실체는 `keeping-redis`/`qr-redis` 로 별개 |
| 2026-08-24 | Bug A 수정: `OutboxPublisher` inner @Transactional 메서드를 `PaymentOutboxRepository` 의 `@Transactional @Modifying @Query` 로 대체 | Self-injection(@Lazy)은 순환 냄새, 별도 @Component는 불필요한 신규 클래스. @Modifying @Query는 프록시를 항상 통과하고 엔티티 로드 없는 단건 UPDATE라 가장 단순하고 효율적 |
| 2026-08-24 | Bug B 수정: `ProcessedEvent` 에 `Persistable<String>` 구현(`@Transient isNew + @PostLoad markNotNew`) | `existsById` 선조회는 TOCTOU 경쟁 조건. `INSERT IGNORE`는 MySQL 전용. `Persistable`이 JPA 표준, DB 중립, 경쟁 조건 없음 |
| 2026-08-24 | 인프라 3종 버그(DDL 타입 불일치·@EnableKafka 누락·JpaConfig 패키지 누락) 사후 예방책: `@SpringBootTest` + `ddl-auto=validate` 컨텍스트 스모크 테스트 CI 추가 | 정적 분석은 DDL vs 엔티티 불일치, 조건부 Config, basePackages 범위를 잡지 못함. 실제 컨텍스트 기동이 유일한 완전 검증 수단 |
