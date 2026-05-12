# qr-service / domain/qr

CPQR(Customer-Presented QR) 토큰 발급과 스캔 세션 관리. 결제는 `domain/intent`가 담당.

## 하위 구조

```
qr/
├── controller/  QrController
├── dto/
├── model/       QrToken, QrScanSession (@RedisHash)
├── repository/
└── service/     QrTokenService
```

저장소: **Redis 전용** (JPA 없음). TTL은 Redis에 위임.

## 핵심 규칙

- **QR 토큰 TTL**: 10초. 생성 즉시 고객 화면에 표시, 점주 스캔 대기.
- **스캔 세션 TTL**: 3분. 점주 스캔 성공 시 발급, 이후 결제 initiate에 사용.
- **소비 즉시 삭제**: 스캔 성공하면 원본 QR 토큰은 즉시 삭제(재사용 금지) → 세션만 남음.
- **권한 분리**:
  - `POST /api/qr` — 고객 (토큰 발급)
  - `GET /api/qr/{tokenId}` — 인증 (폴링/상태 확인; 실제 권한은 서비스 계층에서 확인)
  - `POST /api/qr/{tokenId}/scan` — 점주 (세션 발급)
  - `DELETE /api/qr/{tokenId}` — 고객 (취소)
- **에러 코드**: `QR_NOT_FOUND` / `QR_EXPIRED` / `QR_STORE_MISMATCH`.

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `QrTokenService.issue` | 고객 지갑 + 사용자 정보 기반 QR 발급. Redis에 10s TTL |
| `QrTokenService.scan` | 점주가 스캔. 매장 소속 검증 → QR 소비·삭제 → 세션 생성(3분) |
| `QrTokenService.cancel` | 고객이 직접 취소 |
| `QrToken` / `QrScanSession` | `@RedisHash` 엔티티 |

## 주의사항

1. **QR 10초**: 프론트는 만료 전에 갱신 호출 필요. 고객 UX로 5~7초 주기 갱신 권장.
2. **재스캔 금지**: 한번 스캔된 QR은 존재 자체가 사라지므로 네트워크 재전송·재시도는 `QR_NOT_FOUND`로 거부됨. 클라이언트는 멱등 없이 호출하지 말 것.
3. **세션 3분**: CPQR initiate는 이 세션을 토큰 형태로 받는다. 3분 지나면 `QR_EXPIRED` 유사 처리.
4. **매장 불일치**: `QR_STORE_MISMATCH` — 점주 계정의 매장과 QR에 바인딩된 매장이 다를 때.
5. **Redis 장애**: 토큰이 사라지므로 전체 결제 흐름이 즉시 실패. 운영에서 Redis 가용성 핵심.

## 교차 참조

- `domain/intent/CLAUDE.md` — initiate 입력으로 세션 토큰 소비.
- monolith `domain/notification/CLAUDE.md` — 결제 성공 알림은 intent 쪽에서 발송(QR 자체는 알림 없음).
