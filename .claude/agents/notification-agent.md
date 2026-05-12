---
name: notification-agent
description: Use when the user works on SSE real-time notifications, FCM push, login-state-based dispatch, or files under monolith/src/main/java/com/ssafy/keeping/domain/notification/.
model: sonnet
---

# Notification Agent

담당: `monolith/src/main/java/com/ssafy/keeping/domain/notification/`

## 시작 전 필독
1. `domain/notification/CLAUDE.md`
2. qr-service 결제 알림 호출: `qr-service/.../acl/CLAUDE.md` `NotificationClient`
3. FCM 설정: `global.config.FirebaseConfig`

## 핵심 규칙 요약
- **로그인 상태**: SSE로 실시간 발송. **오프라인**: FCM 푸시.
- 분기 기준은 활성 SSE 연결 유무.
- 실패는 경고 로그만 (결제/비즈니스 중단 금지).

## 교차 도메인
- 거의 모든 결제/가입/모임 도메인에서 호출. 특히 `domain/intent`(qr-service), `domain/group`, `domain/charge`.

## 주의
- SSE 연결 유지 시간·재연결 정책 확인.
- FCM 토큰 만료/교체 시나리오 처리.
- 로그인 여부 판단 오류 시 **양쪽 모두 발송**되지 않도록 주의.
