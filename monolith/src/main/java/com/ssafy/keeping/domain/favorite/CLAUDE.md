# domain/favorite

고객의 매장 찜(즐겨찾기). 소프트 토글 방식. 점주용 찜 개수 조회 제공.

## 하위 구조

```
favorite/
├── controller/   StoreFavoriteController(고객), OwnerFavoriteController(점주)
├── dto/          SimpleFavoriteDto, FavoriteStoreDetailDto, ...
├── model/        StoreFavorite (Customer-Store N:M, active/unfavoritedAt)
├── repository/   StoreFavoriteRepository
└── service/      StoreFavoriteService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `StoreFavorite` | (customer, store) 유니크. `active` boolean + `unfavoritedAt` 타임스탬프로 소프트 딜리트 |
| `StoreFavoriteService.toggleFavorite` | 기존 레코드 있으면 active 토글, 없으면 신규. 재활성화 시 `unfavoritedAt=null` |
| `StoreFavoriteService.getFavoriteStores` | active=true만 `favoritedAt` 내림차순 페이징 |
| `StoreFavoriteService.getStoreFavoriteCount` | 점주 소유 매장만 조회 (`Store.findByStoreIdAndOwner`로 권한 강제) |

## 도메인 규칙

- 삭제 시 레코드 제거 대신 `active=false, unfavoritedAt=now`.
- (customer, store) 유니크 제약 → 한 고객당 한 매장에 레코드 1개.
- 재찜 시 `favoritedAt` 갱신.
- 점주 찜 개수 조회는 자기 매장만 허용.

## 의존

- `domain.user.customer`, `domain.user.owner`, `domain.store`
- `global.exception` (`USER_NOT_FOUND`, `STORE_NOT_FOUND`)
- `global.response.ApiResponse`, `auth.security.UserPrincipal`

## 엔드포인트

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| POST | `/favorites/stores/{storeId}` | 고객 | 찜 토글 |
| GET | `/favorites` | 고객 | 내 찜 목록 (페이지) |
| GET | `/favorites/stores/{storeId}/check` | 고객 | 특정 매장 찜 여부 |
| GET | `/favorites/owner/stores/{storeId}/count` | 점주 | 매장 찜 개수 (컨트롤러 기본 경로는 `/favorites/owner`) |

## 주의사항

1. 재활성화 시 `unfavoritedAt`을 명시적으로 null 처리하지 않으면 쿼리가 오염됨.
2. 페이지 쿼리와 count 쿼리가 분리되어 있어 N+1 주의.
3. `FavoriteStoreDetailDto`는 정의만 되어 있고 사용처가 없음.
4. 점주 권한은 `findByStoreIdAndOwner` 메서드에만 의존 — 네이밍이 바뀌면 권한 누수 위험.
