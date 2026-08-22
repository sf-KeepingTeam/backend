# 최종 정적 검증 보고

## 요약 — 3줄

1. 컴파일은 통과했다 (monolith + qr-service `compileJava` + `compileTestJava` BUILD SUCCESSFUL)
2. **결함 3건 발견**: (1) 테스트-코드 불일치 1건 (런타임 실패 확실), (2) 계약 C-5 태그 위반 1건, (3) 설계안 §8 테스트 대부분 미작성
3. 코드 수정 자체는 계약을 잘 따르고 있으나, **테스트 커버리지가 설계안 요구의 약 30% 수준**이라 "테스트 통과"로 안심할 수 없다

## 판정: 코드 수정 완료 (실행 검증 대기) — 조건부

조건: 아래 §9 결함 3건을 사람이 확인·수정한 뒤 테스트를 실행해야 한다.

---

## 1. 금지 명령 실행 흔적 (§5-1)

- 워크로그에 "테스트 통과", "BUILD SUCCESSFUL" 표현: **0건** ✅
- 컴파일은 사람(사용자)이 돌아와서 직접 실행한 것이므로 위반 아님
- `git --no-pager log --oneline -5` 확인 불가 (읽기 전용 git 금지는 아니지만 이 세션에서는 컴파일 확인이 이미 됨)

## 2. 워크로그 대조 (§5-2)

| 세트 | 워크로그 존재 | 변경 파일 일치 | 경계 침범 |
|---|---|---|---|
| #16 schema | ✅ `16_schema.md` | ✅ keeping.sql, migration/ | 없음 |
| #17 platform | ✅ `17_platform.md` | ✅ global/metrics, config, ErrorCode | 없음 |
| #18 wallet | ✅ `18_wallet.md` (오케스트레이션AI 대필) | ✅ wallet/service, model, repository, dto | 없음 |
| #19 payment | ✅ `19_payment.md` (오케스트레이션AI 직접) | ✅ payment/refund/service | 없음 |
| #20 internal | ✅ `20_internal_refund.md` | ✅ internal/service | 없음 |
| #21 qr | ✅ `21_qr.md` | ✅ qr-service/acl, domain/intent | 없음 |
| #22 internal | ✅ `22_internal_restore제거.md` | ✅ internal/service, controller | 없음 |
| #23 internal | ✅ `23_internal_lazy.md` (오케스트레이션AI 직접) | ✅ internal/service | 없음 |
| #24 wallet | ✅ `24_wallet_reconcile.md` (오케스트레이션AI 대필) | ✅ wallet/service | 없음 |
| #25 internal | ✅ `25_internal_ops.md` (오케스트레이션AI 직접) | ✅ internal/controller | 없음 |

## 3. 컴파일 위험 점검 (§5-3) ★

| # | 점검 | 결과 | 근거 |
|---|---|---|---|
| K-1 | WalletLedgerService 시그니처 일치 | ✅ | 정의: 3개 메서드. 호출부: InternalWalletService(settleExpiredLots, restoreLotsByOriginalTx), PaymentRefundService(restoreLotsByOriginalTx), LotExpiryService(settleExpiredLots), WalletReconciliationService(sumActiveLotRemaining), InternalOpsController(간접) — 전부 일치 |
| K-2 | LotExpiryService/WalletReconciliationService 시그니처 | ✅ | sweepOnce(LocalDateTime), runOnce() — 호출부(스케줄러, OpsController)와 일치 |
| K-3 | record 필드명 | ✅ | ExpirySweepReport, ReconcileReport, MismatchRow — 생성/접근 코드 대조 일치 |
| K-4 | LedgerMetrics 상수·메서드명 | ⚠️ | **CAUSE_LOT_SHORTFALL, CAUSE_EXPIRY_CLAMP, CAUSE_RECONCILE 상수는 존재하나, InternalWalletService:214의 로그 메시지가 `cause=lazy_settle_error`로 계약 외 값 사용** → §9 결함 |
| K-5 | WalletLedgerProperties getter | ✅ | `getExpiry().isLazyEnabled()`, `getExpiry().getMaxGroupsPerRun()`, `getCapture().isStrictLot()`, `getReconcile().getPageSize()` — 전부 호출부와 일치 |
| K-6 | 신설 엔티티 필드 getter/setter | ✅ | WalletStoreLot: `@Getter @Setter` 클래스 레벨. `getExpiredSettledAt()`, `getExpiredAmount()` 사용 가능 |
| K-7 | import 누락 | ✅ | 컴파일 통과로 확인 완료 |
| K-8 | 생성자 주입 필드 | ✅ | 컴파일 통과로 확인 완료 |
| K-9 | ErrorCode 상수 존재 | ✅ | REFUND_ORIGINAL_TX_REQUIRED, FUNDS_INVARIANT_VIOLATION — 전부 enum에 존재 |
| K-10 | 삭제 심볼 잔존 참조 | ⚠️ | `restore` — InternalWalletController에서 서비스 메서드 제거됨. 컨트롤러의 restore 엔드포인트도 제거됨. qr 쪽도 제거됨. **잔존 참조 없음 확인** |
| K-11 | @Param 바인딩 | ✅ | `findExpiryCandidatePairs(:now, :maxGroups)`, `lockUnsettledExpiredLots(:walletId, :storeId, :now)`, `sumActiveLotRemaining(:walletId, :storeId)` — 전부 JPQL/native 파라미터명과 @Param 일치 |
| K-12 | native query 컬럼명 | ✅ | `expired_settled_at`, `expired_at`, `wallet_id`, `store_id`, `lot_status`, `amount_remaining` — 전부 엔티티 @Column과 일치. 마이그레이션 DDL과도 일치 |

## 4. 설계안 §8 대조표 (§5-4)

| ID | 요구 | 테스트 위치 | 판정 |
|---|---|---|---|
| E-1 | lot 만료 정산 + balance 감소 | WalletLedgerServiceTest:152 | ✅ 존재. 정확한 숫자 단언 |
| E-2 | 멱등 마커 — 재실행 시 변화 없음 | WalletLedgerServiceTest:194 | ✅ 존재 |
| E-3 | 만료/미만료 혼재 | WalletLedgerServiceTest:328 (E-7로 대체) | ✅ 유사 |
| E-4 | balance < 만료액 → 클램프 | WalletLedgerServiceTest:225 | ✅ 존재. balance=0 단언 |
| E-5 | 배치 상한 초과 backlog | **❌ 없음** | LotExpiryService 테스트 자체가 없음 |
| E-6 | GROUP 지갑 TRANSFER_IN lot 만료 | **❌ 없음** | |
| L-1 | lazy 정산 후 잔액 부족 | **❌ 없음** | |
| L-2 | lazy-enabled=false 시 갭 존재 | **❌ 없음** | |
| L-3 | lazy 실패 시 결제 성공 | **❌ 없음** | |
| I-1 | 전 구간 불변식 | **❌ 없음** | 통합 테스트 필요 |
| I-2 | 결제+배치 동시 데드락 0 | **❌ 없음** | 실행 필요 |
| I-3 | 결제 20건 동시 | **❌ 없음** | |
| R-1 | processRefund balance+lot 복원 | InternalWalletServiceTest:165 | ✅ 존재 |
| R-2 | originalTxId=null → 400 | InternalWalletServiceTest:116 | ✅ 존재 |
| R-3 | 멱등키 재생 | **❌ 없음** | processRefundIdempotent 테스트 없음 |
| R-4 | move 합계 ≠ 금액 → 롤백 | InternalWalletServiceTest:232 | ✅ 존재 |
| R-5 | restore → 404 | InternalWalletServiceTest:295 | ✅ 리플렉션으로 확인 |
| G-1 | 만료 lot 섞인 해산 정산 | **❌ 없음** | |
| G-2 | existsActiveLotByWalletId | **❌ 없음** | |
| C-1~C-8 | 대사 시나리오 | **❌ 없음** | WalletReconciliationService 테스트 없음 |
| P-1~P-10 | platform 메트릭/프로퍼티/ShedLock | LedgerMetricsTest (8개) | ✅ 메트릭 테스트 존재 |

**요약: 설계안 §8 요구 약 30개 중 테스트가 존재하는 것 약 10개 (30%).**

## 5. 껍데기 테스트 (§5-5) ★

| 테스트 | 냄새 | 판정 |
|---|---|---|
| WalletLedgerServiceTest:116 "C-8: 클램프" | **코드와 불일치** — 테스트는 클램프(remaining=total)를 기대하지만, 코드는 `FUNDS_INVARIANT_VIOLATION` 예외를 던지도록 수정됨 | **❌ 런타임 실패 확실** |
| WalletLedgerServiceTest 나머지 12개 | 전부 구체적 값을 `assertThat`으로 단언 | ✅ 정상 |
| InternalWalletServiceTest 8개 | 전부 구체적 단언 (ErrorCode, 응답 필드, verify) | ✅ 정상 |
| LedgerMetricsTest 8개 | 전부 메트릭 값/존재 확인 | ✅ 정상 |

**결함**: `WalletLedgerServiceTest:116-141` — 오케스트레이션AI가 `restoreLotsByOriginalTx`의 상한 초과 동작을 클램프→예외로 수정했는데, wallet-agent가 작성한 테스트는 클램프 동작을 기대한다. **이 테스트는 반드시 수정해야 한다.**

## 6. 논리 추적 — 불변식 (§5-6)

| 경로 | balance 변화 | lot 변화 | 짝 | 근거 |
|---|---|---|---|---|
| 충전 (PrepaymentConfirmService) | +totalPoints | lot 생성 +totalPoints | ✅ | 기존 코드 무변경 |
| 결제 (capture) | -amount | FIFO lot -amount | ✅ | lazy 정산이 잔액 검사 앞에 배치됨 |
| 결제 취소 (PaymentRefundService) | +amount | restoreLotsByOriginalTx | ✅ | sumRestore != amount 검증 유지 |
| 복구 환불 (processRefund) | +amount | restoreLotsByOriginalTx | ✅ | 신설. sumRestore != amount 검증 |
| 공유/회수 (WalletService) | 대칭 | 대칭 | ✅ | 기존 코드 무변경 |
| 만료 (settleExpiredLots) | -min(balance, sum) | lot.remaining → 0 | ✅ | 클램프 시 불일치 기록 |
| 해산 정산 | 기존 로직 | 만료 lot은 remaining=0이라 자동 제외 | ✅ | G-1 회귀 조건 만족 |

## 7. 계약 위반 (C-1 ~ C-11)

| 계약 | 결과 |
|---|---|
| C-1 불변식 | ✅ 시간 술어 없음. `sumActiveLotRemaining`은 `lot_status=ACTIVE`만 필터 |
| C-2 락 순서 | ✅ balance → lot. LotExpiryService:75, InternalWalletService:198→214, PaymentRefundService:183→201 |
| C-3 DDL | ✅ 컬럼명/타입 일치. LotStatus에 EXPIRED 없음, TransactionType에 EXPIRE 없음 |
| C-4 시그니처 | ✅ 3개 메서드 + 3개 DTO 계약 그대로 |
| C-5 메트릭 | ⚠️ **InternalWalletService:214 로그에 `cause=lazy_settle_error` — C-5 태그 3종에 없는 값** |
| C-6 로그 규약 | ✅ `[LEDGER_MISMATCH]` WARN 이상. `[LOT_EXPIRY]`, `[RECONCILE]` INFO |
| C-7 설정 키 | ✅ 전부 WalletLedgerProperties로 바인딩. @Value 흩뿌림 0건 |
| C-8 ErrorCode | ✅ 신규 1개(REFUND_ORIGINAL_TX_REQUIRED)만 |
| C-9 운영 엔드포인트 | ✅ 경로/인증/반환 일치. validateInternalAuth 호출 확인 |
| C-11 공통 금지 | ✅ Slack 0건, 소유권 이전 0건, balance 제거 0건, 경계 침범 0건 |

## 8. 과장 표현 (§5-7)

- 과장 표현: **0건** ✅
- 워크로그에 "측정하지 못했다", "확인 불가" 표현이 적절히 사용됨
- ShedLock을 "고가용성"으로 포장한 사례: 없음

## 9. 발견한 결함 (수정하지 않고 보고만)

| 심각도 | 위치 | 내용 | 조치 |
|---|---|---|---|
| **높음** | `WalletLedgerServiceTest:116-141` | 테스트가 상한 초과 시 클램프를 기대하지만 코드는 `FUNDS_INVARIANT_VIOLATION` 예외를 던짐. **이 테스트는 실행 시 반드시 실패한다** | 테스트를 `assertThrows(CustomException.class)` + ErrorCode 검증으로 수정 |
| **중간** | `InternalWalletService:214` | 로그 `cause=lazy_settle_error`가 C-5 태그 3종(`lot_shortfall`/`expiry_clamp`/`reconcile`)에 없음. 메트릭은 `CAUSE_EXPIRY_CLAMP`로 기록하는데 로그 메시지가 다른 값 | 로그 메시지를 `cause=expiry_clamp`로 통일하거나, 새 태그를 C-5에 추가 |
| **중간** | 설계안 §8 테스트 | 요구 약 30개 중 10개만 존재 (30%). 특히 LotExpiryService, WalletReconciliationService, lazy 정산(L-1~L-4), strict-lot(S-1~S-3) 테스트가 전무 | 복귀 후 테스트 추가 필요 |

## 10. 확인하지 못한 것 ★

전부 `복귀체크리스트.md`에 있다. 하나도 ✅로 표시할 수 없다.

- 테스트 실행 결과 (./gradlew test)
- 클린 DB 전 구간 시나리오 후 불변식 성립
- 데드락 0 (SHOW ENGINE INNODB STATUS)
- /internal/ops/* 실동작
- /actuator/prometheus 메트릭 7개 실노출
- Gauge가 NaN이 아닌지
- lazy on/off 성능 비교
- prod 스키마 대조
- FOR UPDATE SKIP LOCKED 실서버 동작
- EXPLAIN 결과
- ALTER 소요 시간
