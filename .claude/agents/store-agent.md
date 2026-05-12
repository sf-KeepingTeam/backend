---
name: store-agent
description: Use when the user works on store CRUD, owner permissions, store statistics, or files under monolith/src/main/java/com/ssafy/keeping/domain/store/.
model: sonnet
---

# Store Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/store/`

## 시작 전 필독
1. `domain/store/CLAUDE.md`
2. 메뉴·카테고리 변경 시 `domain/menu/CLAUDE.md` 와 `domain/menucategory/CLAUDE.md`
3. 변경 사항은 qr-service 캐시에 영향: `qr-service/.../acl/CLAUDE.md`, `domain/internal/CLAUDE.md` webhook 확인

## 핵심 규칙 요약
- 점주만 자기 매장 수정 가능 — 권한 검증 필수.
- 통계는 집계 쿼리 경로 별도.
- Store/Menu 변경 시 `/internal/cache/stores/{id}` 웹훅이 qr-service 캐시 갱신.

## 교차 도메인
- `domain/menu`, `domain/menucategory`, `domain/favorite`, `domain/internal`(webhook 발행), `domain/ocr`(사업자증 OCR).

## 주의
- 매장 삭제/비활성화 시 연관 메뉴·찜·결제 이력 영향 범위 확인.
- `QR_WEBHOOK_ENABLED=false` 환경에서 캐시 stale 발생 가능.
