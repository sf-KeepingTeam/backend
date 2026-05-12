# domain/store

매장 CRUD + 점주 통계. 소프트 딜리트와 상태 전환(ACTIVE/SUSPENDED/DELETED).

## 하위 구조

```
store/
├── constant/     StoreStatus
├── controller/   StoreController(공개), OwnerStoreController(점주), StoreStatisticsController(점주)
├── dto/          StorePublicDto, StoreResponseDto, StoreRequestDto, 통계 DTO
├── model/        Store
├── repository/   StoreRepository
└── service/      StoreService, StoreStatisticsService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `Store` | 매장 엔티티. Owner N:1 필수. Wallet/Lot/Transaction 1:N |
| `StoreStatus` (enum) | ACTIVE / SUSPENDED / DELETED |
| `StoreService` | CRUD, 중복 등록 검증, 점주-매장 권한, 이미지 업로드, qr-service 캐시 webhook 발행 |
| `StoreStatisticsService` | 누적/일별/월별/기간별 통계. 8개 쿼리 조합 |
| `StoreRepository` | 공개용(ACTIVE+deletedAt null)과 점주용 쿼리 분리 + 캐시 워밍용 전량 조회 |

## 도메인 규칙

- **중복 등록 방지**: `(taxIdNumber, address)` 기준. 생성/수정 모두 검증(`STORE_ALREADY_EXISTS`).
- **수정 가능 상태**: ACTIVE만 (`STORE_INVALID`).
- **소프트 딜리트**:
  - 미정산 잔액 존재 → SUSPENDED
  - 잔액 0 → DELETED + `deletedAt=now`
  - 잔액 확인은 `existsPositiveBalanceForStoreWithLock`(비관락)
- **점주 권한**: 모든 수정/삭제/통계에서 `storeId + ownerId` 일치 확인. `validateStoreOwnership` 헬퍼 존재.
- **공개 조회**: ACTIVE + deletedAt null만. `StorePublicDto`로 필터링된 필드 노출.
- **qr-service webhook**: 생성/수정/삭제 시 `QrServiceWebhookPublisher` 비동기 발행.

## 의존

- `domain.user.owner`
- `domain.payment.transactions` (통계)
- `domain.wallet` (미정산 확인)
- `global.s3.ImageService`
- `domain.internal.webhook.QrServiceWebhookPublisher`
- `global.exception` (`STORE_*`, `OWNER_NOT_MATCH`)

## 엔드포인트

**`/api` 프리픽스 없음.** 검색·카테고리 필터는 별도 path가 아니라 `@GetMapping(params=...)` 기반 쿼리스트링 매칭.

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| GET | `/stores` | - | 전체 ACTIVE (params={"!name","!category"}) |
| GET | `/stores?category=...` | - | 카테고리별 |
| GET | `/stores?name=...` | - | 이름 검색 |
| GET | `/stores/{storeId}` | - | 상세 |
| GET | `/owners/stores` | 점주 | 내 매장 목록 |
| GET | `/owners/stores/{storeId}` | 점주 | 내 매장 상세 |
| POST | `/owners/stores` | 점주 | 등록 (multipart) |
| PATCH | `/owners/stores/{storeId}` | 점주 | 수정 (multipart) |
| DELETE | `/owners/stores/{storeId}` | 점주 | SUSPENDED/DELETED 전환 |
| GET | `/stores/{storeId}/statistics/overall` | 점주 | 누적 통계 |
| POST | `/stores/{storeId}/statistics/daily` | 점주 | 일별 (※ POST) |
| POST | `/stores/{storeId}/statistics/period` | 점주 | 기간별 (※ POST) |
| POST | `/stores/{storeId}/statistics/monthly` | 점주 | 월별 (※ POST) |

## 주의사항

1. 수정 시 `(taxIdNumber, address)` 중복 검사가 **현재 taxId + 새 address** 조합도 체크하는지 확인.
2. 점주 검증 순서: Store 존재 → 점주 일치. 순서 반대면 에러 메시지가 오해를 유발.
3. SUSPENDED는 "잔액이 남아 아직 지워지지 않음" 의미 — 완전 삭제가 아님.
4. 통계 쿼리가 8개 개별 조회 — 점주 대시보드 요청 시 성능 모니터링 필수.
5. 월별 통계의 일평균은 `lengthOfMonth()` — 달마다 분모가 다름.
6. 기간별 통계의 end는 `endDate + 1` — 경계 포함 주의.
7. 이미지 업로드 실패/null 처리 — `imgUrl`은 null 가능 여부 코드 기준으로 확인.
