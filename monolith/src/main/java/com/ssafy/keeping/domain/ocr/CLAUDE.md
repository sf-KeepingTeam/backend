# domain/ocr

이미지 OCR 두 축: (1) **사업자등록증** → Clova 템플릿 OCR, (2) **메뉴판** → OpenAI GPT-4V.

## 하위 구조

```
ocr/
├── controller/   OcrController
├── dto/          BizLicenseOcrResponse, MenuOcrResponse, MenuOcrItem
└── service/      BizLicenseOcrService, MenuOcrService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `OcrController` | 2 엔드포인트(사업자증, 메뉴판). 공통 파일 검증(타입/용량) |
| `BizLicenseOcrService` | Clova Template OCR. multipart(message JSON + 파일 바이너리). 응답 정규화(사업자번호 하이픈, 개업일자 YYYY-MM-DD) |
| `MenuOcrService` | OpenAI WebClient. Base64 인코딩 이미지. JSON Mode 강제. 이름 공백 정규화 후 중복 제거(같은 이름은 최저가) |

## 도메인 규칙

- **파일 제한**: jpg/jpeg/png Content-Type만, **최대 10MB**.
- **Clova 요청**: `X-OCR-SECRET` 헤더, message에 `requestId`(UUID), `timestamp`(epoch ms), `version` = V2, `templateIds` 포함. 이미지 format은 ContentType/확장자 추론.
- **정규화 규칙**:
  - 사업자번호: `등록번호`/`사업자등록번호` 우선 매칭, 10자리면 `XXX-XX-XXXXX` 포맷.
  - 개업일자: 점/슬래시/한글 `년월일` → `YYYY-MM-DD`.
  - 신뢰도: 모든 필드 confidence 평균.
- **메뉴 OCR**:
  - 가격 유효 범위: 0 ~ 2,000,000원
  - 중복 제거: 이름 공백 제거 키. 동명 항목은 최저가만 유지.
- **설정**:
  - `clova.ocr.url`, `clova.ocr.secret`, `clova.ocr.template-ids`(쉼표 문자열 → 정수 배열)
  - `openai.api.key`, `openai.api.url` (모델은 `MenuOcrService`에 `"gpt-4o"`로 **하드코딩**되어 있음)

## 의존

- `global.exception` (`OCR_*` 코드 다수)
- `global.response.ApiResponse`
- 외부: Clova OCR, OpenAI Chat Completions API

## 엔드포인트

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| POST | `/ocr/biz-license` | 인증(점주) | multipart 이미지 (**`/api` 프리픽스 없음**) |
| POST | `/ocr/menu` | 인증(점주) | multipart 이미지 |

## 주의사항

1. `templateIds` 설정 누락 시 `IllegalStateException` — 템플릿 ID 사전 등록 필수.
2. Clova 템플릿마다 필드명이 다름 — 현재 한글 라벨 우선 매칭. 템플릿 변경 시 매핑 재확인.
3. 메뉴 중복 제거가 공백 기준 — "떡볶이"와 "떡 볶이"는 다른 항목으로 처리됨.
4. OpenAI 응답의 `choices[0].message.content`는 JSON 문자열 — 한 번 더 파싱 필요.
5. 날짜 파싱 실패 시 숫자 추출 방식 폴백 — 예상치 못한 형식은 검증 불가.
6. 10MB 초과 업로드는 MultipartResolver 단계에서 차단되도록 Spring 설정도 맞춰두기.
