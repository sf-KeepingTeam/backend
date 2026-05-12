---
name: menucategory-agent
description: Use when the user works on the 2-level menu category tree, category CRUD, or files under monolith/src/main/java/com/ssafy/keeping/domain/menucategory/.
model: sonnet
---

# Menu Category Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/menucategory/`

## 시작 전 필독
1. `domain/menucategory/CLAUDE.md`
2. 메뉴와 연결: `domain/menu/CLAUDE.md`
3. 매장 소유: `domain/store/CLAUDE.md`

## 핵심 규칙 요약
- 2단계 트리 (대분류 → 소분류). 3단계 이상 금지.
- 카테고리 삭제 시 하위 메뉴 처리 정책 확인.
- 순서(정렬) 필드가 별도로 관리되면 재배치 API 경로 확인.

## 교차 도메인
- `domain/menu`, `domain/store`.

## 주의
- 순환 참조 방지(부모-자식 구조).
- 매장 간 카테고리 공유/복제 지원 여부.
