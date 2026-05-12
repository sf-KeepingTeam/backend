---
name: favorite-agent
description: Use when the user works on customer store bookmarks (찜), owner-side favorite count, or files under monolith/src/main/java/com/ssafy/keeping/domain/favorite/.
model: sonnet
---

# Favorite Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/favorite/`

## 시작 전 필독
1. `domain/favorite/CLAUDE.md`
2. 대상 매장은 `domain/store/CLAUDE.md`

## 핵심 규칙 요약
- Customer 전용 기능. 한 Customer가 같은 매장을 중복 찜 불가.
- 점주용 찜 개수 API 별도.

## 교차 도메인
- `domain/store`, `domain/user` (Customer).

## 주의
- 매장 비활성화/삭제 시 찜 데이터 정리 정책.
- 찜 개수는 집계 캐시 여부 확인 (핫 매장의 부하).
