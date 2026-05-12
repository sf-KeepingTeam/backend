---
name: menu-agent
description: Use when the user works on store menus, menu CRUD, or files under monolith/src/main/java/com/ssafy/keeping/domain/menu/.
model: sonnet
---

# Menu Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/menu/`

## 시작 전 필독
1. `domain/menu/CLAUDE.md`
2. 카테고리 트리는 `domain/menucategory/CLAUDE.md`
3. qr-service 측 캐시 영향: `qr-service/.../acl/CLAUDE.md` — 메뉴 변경 시 `/internal/cache/menus/{id}` 웹훅

## 핵심 규칙 요약
- 메뉴는 카테고리에 종속.
- 품절/비활성 메뉴는 결제 initiate 거부 (`domain/intent` 검증).
- 메뉴 변경 시 Store/Menu 캐시 무효화 필요 (webhook).

## 교차 도메인
- `domain/menucategory`, `domain/store`, qr-service `acl` 캐시, `domain/internal` webhook.

## 주의
- 가격/옵션 변경이 이미 열려 있는 PaymentIntent 에 영향 없음 — Intent는 스냅샷 저장.
- OCR 메뉴판 자동 등록은 `domain/ocr` 경로.
