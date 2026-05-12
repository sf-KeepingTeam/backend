# domain/menucategory

매장 메뉴 카테고리(2단계 트리). 대분류(parent=null)와 세분류(parent!=null).

> **주의**: 이전 패키지명은 `menuCategory` (CamelCase). CLAUDE.md 재구성과 함께 `menucategory`로 rename됨.

## 하위 구조

```
menucategory/
├── controller/   MenuCategoryController(공개), OwnerMenuCategoryController(점주)
├── dto/
├── model/        MenuCategory (self-reference)
├── repository/
└── service/      MenuCategoryService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `MenuCategory` | 카테고리 엔티티. `parent` 자기참조, `displayOrder` |
| `MenuCategoryService` | 권한/부모 존재/중복명 검증, 자식 존재 시 삭제 차단, 부모 변경 시 displayOrder 재계산 |
| `MenuCategoryController` | 공개 — 대분류(parent=null)만 반환 |
| `OwnerMenuCategoryController` | 점주 CRUD |

## 도메인 규칙

- **2단계만 지원** (parent의 parent 방지는 코드 차원에서 강제하지 않음).
- **유니크 제약 2개**:
  - (store, parent, name) — 같은 부모 하위 이름 중복 금지
  - (store, parent, displayOrder) — 같은 부모 하위 순서 중복 금지
  - `parent`는 nullable — 대분류와 세분류 모두 이 제약 하나로 관리
- **displayOrder 재계산**: 부모 변경 시만. 이름만 변경 시 유지.
- **삭제 조건**: 자식 카테고리 없을 때만. 메뉴 존재 여부는 FK 제약으로만 판정(코드에서 선검증 없음).
- **공개 API**는 대분류만 노출 — 세분류 조회는 별도 경로 필요.

## 의존

- `domain.store`, `domain.user.owner`
- `domain.menu` (Menu FK 제약으로 간접 결합)
- `global.exception` (`MENU_CATEGORY_NOT_FOUND`, `MENU_CATEGORY_HAS_CHILDREN`, `DUPLICATE_RESOURCE`)

## 엔드포인트

**`/api` 프리픽스 없음.** (참고: `MenuCategoryController`의 `@RequestMapping`은 선두 `/` 없이 `"stores/..."`로 선언되어 있지만 Spring이 정규화하므로 실제 매핑은 `/stores/...` 와 동일하게 동작)

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| GET | `/stores/{storeId}/menus/categories` | - | 대분류 목록 |
| POST | `/owners/stores/{storeId}/menus/categories` | 점주 | 생성 |
| PATCH | `/owners/stores/{storeId}/menus/categories/{categoryId}` | 점주 | 수정 |
| DELETE | `/owners/stores/{storeId}/menus/categories/{categoryId}` | 점주 | 삭제(자식 없을 때). **⚠️ 코드 버그**: `OwnerMenuCategoryController`의 `@DeleteMapping("{categoryId}")` 선두 슬래시 누락으로 실제 매핑은 `/owners/stores/{storeId}/menus/categories{categoryId}` — Spring이 자동 정규화하지 않으면 호출 실패. 코드 수정 권장 |

## 주의사항

1. 부모 변경 시 displayOrder 초기화 — 이름+부모 동시 변경 요청은 부모 변경만 유효한 듯 보일 수 있음.
2. 삭제 시 Menu 잔존 확인 없음 — FK 위반으로 DB에서만 막힘.
3. 부모 카테고리의 store와 새 카테고리의 store 일치 여부 검증 필요.
4. 중복명 검사 쿼리에서 수정 시 자기 자신 제외를 위해 categoryId 파라미터 필요.
5. 세분류 조회는 별도 엔드포인트(현재 공개 미구현) — 점주 API로 내려가야 함.
