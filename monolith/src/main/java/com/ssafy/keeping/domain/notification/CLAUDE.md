# domain/notification

SSE(실시간) + FCM(푸시) 알림. 포그라운드/백그라운드/로그아웃 상태에 따라 전달 경로 분기.

## 하위 구조

```
notification/
├── controller/   NotificationController(SSE/목록/읽음), FcmController(토큰 등록/삭제)
├── dto/
├── entity/       Notification, FcmToken, NotificationType
├── repository/
└── service/      NotificationService, NotificationQueryService, FcmService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `Notification` | Customer/Owner 중 하나가 수신자. type, content, isRead |
| `FcmToken` | Customer/Owner별 FCM 토큰 + 업데이트 시각 |
| `NotificationType` (enum) | 결제/정산/그룹 **18개** 타입 (POINT_CHARGE, PERSONAL_POINT_USE, GROUP_POINT_USE, POINT_CANCELED, PAYMENT_APPROVED, PAYMENT_REQUEST, PAYMENT_CANCELED, SETTLEMENT_COMPLETED, GROUP_INVITE, GROUP_JOIN_REQUEST, GROUP_JOIN_ACCEPTED, GROUP_JOIN_REJECTED, GROUP_JOINED, GROUP_LEADER_CHANGED, MEMBER_EXPELLED, GROUP_POINT_SHARED, GROUP_LEFT, GROUP_DISBANDED) |
| `NotificationService` | SSE 구독, 활성 SSE 여부로 전달 분기, Redis의 Refresh 존재 여부로 로그인 상태 판단 |
| `NotificationQueryService` | 목록/개수/읽음 처리 (읽기 전용) |
| `FcmService` | 토큰 CRUD + 실제 FCM 발송. 무효 토큰 자동 삭제 |
| `EmitterRepository` | 메모리 `ConcurrentHashMap` 기반 SSE Emitter 관리 + 이벤트 캐시 |

## 도메인 규칙

- **3단계 전달 전략**:
  1. 활성 SSE → SSE push
  2. 활성 SSE 없음 + 로그인 상태 → FCM push
  3. 로그아웃 → DB 저장만
- **로그인 상태 판별**: `auth:rt:{ROLE}:{userId}` Redis 키 존재 여부. 예외 시 안전하게 true 반환(알림이 사라지지 않도록).
- **재연결 시 유실 복구**: Access 토큰 발급 시각 이후의 캐시된 이벤트만 재전송.
- **SSE TTL**: 60분. 완료/타임아웃/에러 모두 Emitter 정리.
- **비동기 트랜잭션**: `sendToCustomer`는 `Propagation.REQUIRES_NEW` — 원 트랜잭션 커밋/롤백과 독립. **`sendToOwner`에는 별도 트랜잭션 전파 속성이 없음** (일반 메서드, 호출자 트랜잭션에 참여).
- **FCM 무효 토큰**: `MessagingErrorCode`로 감지 → 자동 `deleteByToken`.
- **Firebase 초기화**: `FirebaseConfig`가 `fcm.service-account-file` 경로(기본 `classpath:firebase/keeping-firebase-adminsdk.json`)로 `@PostConstruct` 초기화.

## 의존

- `domain.user.customer`, `domain.user.owner`
- `domain.auth.token` (Access issuedAt 추출)
- Redis `StringRedisTemplate`
- `global.config.FirebaseConfig`
- 역방향으로 `group`, `payment.refund`, `internal`, `charge` 등에서 호출

## 엔드포인트

컨트롤러는 `userId`를 **경로 파라미터로 요구** — SecurityContext에서 추출하는 대신 URL에 포함.

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| GET | `/api/notifications/subscribe/customer/{customerId}` | 고객 | SSE 구독 (text/event-stream) |
| GET | `/api/notifications/subscribe/owner/{ownerId}` | 점주 | SSE 구독 |
| GET | `/api/notifications/customer/{customerId}` | 고객 | 알림 목록(페이지) |
| GET | `/api/notifications/owner/{ownerId}` | 점주 | 알림 목록(페이지) |
| GET | `/api/notifications/customer/{customerId}/unread` | 고객 | 미읽음 목록 |
| GET | `/api/notifications/owner/{ownerId}/unread` | 점주 | 미읽음 목록 |
| GET | `/api/notifications/customer/{customerId}/unread-count` | 고객 | 미읽음 개수 |
| GET | `/api/notifications/owner/{ownerId}/unread-count` | 점주 | 미읽음 개수 |
| PUT | `/api/notifications/customer/{customerId}/{notificationId}/read` | 고객 | 읽음 처리 |
| PUT | `/api/notifications/owner/{ownerId}/{notificationId}/read` | 점주 | 읽음 처리 |
| POST | `/api/fcm/customer/{customerId}/token` | 고객 | FCM 토큰 등록/업데이트 |
| POST | `/api/fcm/owner/{ownerId}/token` | 점주 | FCM 토큰 등록/업데이트 |
| DELETE | `/api/fcm/token` | 인증 | 토큰 삭제 |

## 주의사항

1. SSE 연결이 일시적으로 끊긴 상태에서 FCM까지 발송되면 프론트에 중복 알림이 뜰 수 있음.
2. `REQUIRES_NEW` 오버헤드 — 대량 발송 시 DB 커넥션 풀 고갈 주의(예: 모임 공지 브로드캐스트). 단, 이는 `sendToCustomer`에만 해당하고 `sendToOwner`는 호출자 트랜잭션에 참여하므로 본 트랜잭션 실패 시 알림도 롤백되는 차이를 유의할 것.
3. Emitter 누수는 앱 재시작까지 지속됨 — 예외 처리 경로 꼼꼼히.
4. Customer와 Owner에 동시 발송 불가 — `Notification` 엔티티가 단일 수신자 모델.
5. `getTokenIssuedAt` 예외 시 재전송 전부 차단 — 토큰 갱신 전후 경계 케이스 확인.
6. 같은 알림 다기기 동시 읽음 요청 → `NOTIFICATION_ALREADY_READ` 경합. 낙관락 고려 가능.
