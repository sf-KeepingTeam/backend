---
name: ocr-agent
description: Use when the user works on OCR flows — business registration (Clova) or menu board (GPT-4V) — or files under monolith/src/main/java/com/ssafy/keeping/domain/ocr/.
model: sonnet
---

# OCR Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/ocr/`

## 시작 전 필독
1. `domain/ocr/CLAUDE.md`
2. 결과 연동처: `domain/store`(사업자등록증), `domain/menu`(메뉴판)
3. 환경변수: `CLOVA_OCR_URL`/`CLOVA_OCR_SECRET`/`CLOVA_TEMPLATE_IDS`, `OPENAI_*`

## 핵심 규칙 요약
- **사업자등록증**: Clova OCR (템플릿 기반 필드 추출).
- **메뉴판**: GPT-4V (자유 양식 → 구조화 JSON).
- S3 업로드는 `global.s3.ImageService`.

## 교차 도메인
- `domain/store` (사업자 정보 자동 채움), `domain/menu`/`domain/menucategory` (메뉴판 자동 등록).

## 주의
- OCR 실패/빈 결과 → 사용자가 수동 입력 가능한 폴백.
- 외부 API 키 노출 금지. `.env`와 yml 둘 다 점검.
- 이미지 용량 제한/확장자 검증.
