# ADR-003: LOT 만료 처리와 이중 장부 정합성

- **상태**: 승인 (2026-08-21)
- **근거 문서**: `docs/v2/설계안_작업1_LOT만료와_이중장부정합성.md`

## 컨텍스트

Keeping은 포인트 잔액을 두 군데에 기록한다:
- `wallet_store_balances.balance` — 현재 잔액 숫자 하나
- `wallet_store_lot` — 충전 건별 덩어리 (amount_total, amount_remaining, expired_at)

불변식: `SUM(lot.amount_remaining WHERE lot_status='ACTIVE') == balance` (wallet_id, store_id 단위)

전수 조사 결과 이 불변식을 깨는 경로가 4개 확인되었다:
1. LOT 만료 배치가 존재하지 않음 — 만료 시 balance가 그대로라 유령 잔액 발생
2. `capture()`가 lot 부족을 로그만 찍고 커밋
3. `restore()`가 lot 복원 없이 balance만 올림 (죽은 경로)
4. `processRefund()`도 lot 미복원 (살아있는 경로 — qr 복구 스케줄러가 10초마다 호출)

## 결정

### 1. 만료 표현: 배치 + lazy 정산 + 대사 조합

만료는 시간이 트리거라 다른 write 경로와 달리 한 트랜잭션에 묶을 수 없었다. 그래서 배치 + lazy 정산 + 대사 조합을 택했다.

- **배치**: 매일 03:10 KST, 미정산 만료 lot을 스캔해 `amount_remaining=0`, `expired_amount=이전잔량`, `expired_settled_at=now` 처리
- **lazy 정산**: 결제 임계 경로(`capture`)에서 잔액 검사 전에 만료 정산을 한 번 더 수행 → 갭 0
- **대사**: 매일 03:40 KST, 전 지갑 대조. 깨진 것을 발견하는 장치

### 2. `LotStatus.EXPIRED`와 `Transaction(EXPIRE)` 기각

`LotStatus.EXPIRED`와 `Transaction(EXPIRE)`를 기각한 이유:

- `EXPIRED` 상태를 추가하면 DDL CHECK 제약 재작성 + `lot_status='ACTIVE'` 조건 8곳 재검토가 따라온다. prod는 `ddl-auto=validate`라 실수 = 기동 실패.
- `EXPIRE` 거래를 만들면 `transactions.customer_id`(NOT NULL)를 채워야 하는데, **GROUP 지갑에는 `Wallet.customer`가 null이라 `transactions.customer_id`를 채울 수 없다.** 기여자의 customer를 넣는 것은 거짓 기록이다.

대신 lot 자체에 `expired_settled_at`(멱등 마커) + `expired_amount`(소멸액)로 근거를 남긴다.

### 3. `wallet_store_balances` 유지

`wallet_store_balances` 제거는 조회 성능이 아니라 **balance 행이 결제의 직렬화 앵커**(단일행 비관락)이기 때문에 기각했다. `SUM(lot)`으로 바꾸면 락 대상이 행 집합이 되고, 충전의 lot INSERT가 팬텀이 되어 갭 락으로 충전과 결제가 서로를 블로킹한다. 결제 락 구조를 통째로 다시 짜야 한다.

장부를 하나로 줄이는 게 정답이지만 결제 락 구조를 다시 짜야 해서 지금은 하지 않는다. 대신 "lot이 원장, balance는 파생값"을 명시하고 대사 배치로 정합성을 강제한다.

### 4. `lotLeft != 0` 3단계 전환

- 1단계 (이번): 커밋 유지 + 구조화 로그/메트릭으로 관측 승격. 발생원(만료/restore/refund)을 동시에 차단
- 2단계: 대사로 전 지갑 불일치 실측 0 확인
- 3단계: `wallet.capture.strict-lot=true` → 롤백 전환 (설정 한 줄)

## 결과

- 만료 갭이 0이 된다 (lazy 정산)
- 불변식에서 시간 술어가 사라진다 — 대사가 시계에 의존하지 않게 된다
- 기존 결제 락 구조가 무손상이다

## 정직하게 남기는 한계

- lazy 정산의 성능 영향은 아직 숫자가 없다. 인덱스 SELECT 1회라 미미할 것으로 예상하지만 측정이 아니다
- `keeping.sql`은 이미 코드보다 뒤처져 있다. prod 실 스키마와의 차이는 미확인
- 이 작업은 "앞으로 안 깨지게" 만들 뿐, 이미 깨진 존량 데이터를 고치지 않는다
