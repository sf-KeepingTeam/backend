# 실패 기록 (인덱스)

<!-- 규칙: 실패 발생 → 한 줄 기록 → "매번 예외 없이 차단돼야 하는가?" 판단
     예 → 훅/권한 규칙으로 승격 (승격 컬럼에 기록)   아니오 → CLAUDE.md 지침 추가
     같은 실패가 승격 후에도 재발하면 승격 방식이 틀린 것 — 재검토. -->

| 날짜 | 실패 내용 | 원인 | 승격 (훅/지침/보류) |
|---|---|---|---|
| 2026-08-21 | QrToken 직렬화 비대칭으로 scan 500 | isExpired()가 getter 모양이라 Jackson이 프로퍼티로 인식 | 지침: Redis 저장 모델의 파생 메서드에 @JsonIgnore + 왕복 테스트 의무화. redisObjectMapper 분리로 방어막 추가 |
| 2026-08-23 | 상태 전이 후 같은 트랜잭션에서 예외 → 전이가 롤백 (2회차: #45 `markDeclined`, #48 `markExpired`) | 실패 처리를 트랜잭션 안에서 끝내려 함 | 지침(CLAUDE.md 승격 검토 대상): 결제 실패 상태 전이는 `REQUIRES_NEW` 헬퍼에서만. 같은 메서드에 `mark*` 와 `throw` 가 함께 있으면 리뷰에서 잡는다 |
| 2026-08-23 | 정적검증에서 "폴백 경로라 우선순위 낮음"으로 오판 (#46 O-2) | 플래그 기본값(`split-transaction=true`)을 확인하지 않고 경로 중요도를 추정함 | 지침: "폴백/레거시라서 낮음" 판정 전에 해당 플래그의 **기본값과 yml 값**을 근거로 인용할 것 |
| 2026-08-24 | Bug A — OutboxPublisher self-invocation: 327K 중복 발행, 125행 전부 PENDING 무한 루프 | `@Scheduled` 메서드 내에서 `this.fetchPending()` 등 `this.*@Transactional` 호출 → Spring AOP 프록시 우회 → 트랜잭션 미개방 → dirty-check UPDATE 미실행 | 수정: inner @Transactional 메서드 제거, `@Modifying @Query` 리포지토리 메서드로 대체. 지침: 같은 빈의 self-invocation에 @Transactional/@Async/@Cacheable 를 달면 무효. 스케줄러·서비스 내부 private/public 호출은 별도 @Component 또는 리포지토리 쿼리로 위임 |
| 2026-08-24 | Bug B — ProcessedEvent 중복 삽입 미차단: 233K 중복 알림, `duplicate_skipped_total` 0 | `@Id` 필드에 `@GeneratedValue` 없음 → `SimpleJpaRepository.save()`가 `isNew()=false`로 판단해 `persist()` 대신 `merge()` 호출 → UNIQUE 제약 위반이 발생하지 않아 `DataIntegrityViolationException` 미발생 | 수정: `Persistable<String>` 구현, `@Transient boolean isNew=true`, `@PostLoad markNotNew()`. 지침: 수동 할당 `@Id` 엔티티(String UUID 등)는 반드시 `Persistable` 구현 또는 `existsById` 사전 체크 |
