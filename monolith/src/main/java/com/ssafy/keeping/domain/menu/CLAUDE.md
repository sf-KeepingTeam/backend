# domain/menu

매장 메뉴 관리(생성/수정/삭제/조회, 공개/판매여부). 카테고리(`menucategory`)에 종속.

## 하위 구조

```
menu/
├── controller/   MenuController(고객 공개), OwnerMenuController(점주 CRUD)
├── dto/
├── model/        Menu
├── repository/   MenuRepository
└── service/      MenuService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `Menu` | 메뉴 엔티티. Store/MenuCategory 필수 참조. `displayOrder`, `soldOut`, `active`, `imgUrl`, `deletedAt`(소프트 딜리트) |
| `MenuService` | 권한 검증, displayOrder 재계산, 중복명 검사(lower — **`createMenu`에서만**), S3 이미지 업로드, qr-service 캐시 webhook 발행 |
| `MenuController` | 공개 조회 — `active=true`만 반환 |
| `OwnerMenuController` | 점주 CRUD |
| `MenuRepository` | 손님용(`active=true` + `deletedAt=null`)과 점주용 쿼리 분리 |

## 도메인 규칙

- **유니크 제약**: (storeId, categoryId, displayOrder).
- **최소 가격**: 1000원 이상.
- **카테고리 변경 시** 기존 순서 버리고 **새 카테고리 끝 순서**로 재배정.
- **삭제**: 물리 삭제 아님 — `deletedAt` 기입. `nextOrderIncludingDeleted`가 삭제된 메뉴도 순서 계산에 포함.
- **공개/판매**: `active=false`는 고객에게 미노출, `soldOut=true`는 노출되나 품절 표시.
- **중복명 검사**: `lower()` 기반 — 대소문자만 다른 건 중복으로 판정. **주의: `createMenu`에서는 호출되지만 `editMenu`에서는 호출하지 않음** — 이름 변경 시 중복 검사 우회됨. 또한 쿼리가 `(storeId, name)` 기준이라 카테고리를 넘어서도 중복 판정.
- **qr-service 캐시 갱신**: CUD 시 `QrServiceWebhookPublisher`로 비동기 발행(fire-and-forget).

## 의존

- `domain.store`, `domain.menucategory`, `domain.user.owner`
- `global.s3.ImageService`
- `domain.internal.webhook.QrServiceWebhookPublisher`
- `global.exception` (`MENU_NOT_FOUND`, `MENU_CROSS_STORE_CONFLICT`, `MENU_UNAVAILABLE`)

## 엔드포인트

**`/api` 프리픽스 없음.**

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| GET | `/stores/{storeId}/menus` | - | 고객용 전체 메뉴 |
| GET | `/stores/{storeId}/menus/categories/{categoryId}` | - | 카테고리별 메뉴 |
| GET | `/owners/stores/{storeId}/menus` | 점주 | 점주용 목록 |
| POST | `/owners/stores/{storeId}/menus` | 점주 | 생성 (multipart) |
| PATCH | `/owners/stores/{storeId}/menus/{menusId}` | 점주 | 수정 (multipart) |
| DELETE | `/owners/stores/{storeId}/menus/{menusId}` | 점주 | 소프트 삭제 |
| DELETE | `/owners/stores/{storeId}/menus` | 점주 | 일괄 삭제 |

## 주의사항

1. 고객용/점주용 쿼리 혼동 금지 — `active` 필터 차이.
2. 카테고리 변경 후 displayOrder 재설정 — 프론트에서 순서 먼저 저장해도 무효화됨.
3. qr-service 캐시 webhook은 비동기 — 즉시 반영 안 됨.
4. `nextOrderIncludingDeleted`가 삭제 포함 카운트 — 재삭제 반복 시 순서 번호 급증.
5. 중복명 `lower()` 기반 — "콜라"/"COLA"는 다름, "cola"/"Cola"는 중복.
