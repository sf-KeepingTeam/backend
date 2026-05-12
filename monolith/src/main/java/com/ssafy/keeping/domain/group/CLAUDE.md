# domain/group

모임(그룹) 생성·가입·승인·해체와 모임장 권한 관리. 모임 해체/탈퇴 시 공유 지갑 정산 트리거.

## 하위 구조

```
group/
├── constant/
├── controller/   GroupController
├── dto/
├── model/        Group, GroupMember, GroupAddRequest
├── repository/
└── service/      GroupService
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `Group` | 모임. 이름, 설명, 12자리 초대 코드(UUID 기반) |
| `GroupMember` | (group, user) 매핑. `leader` boolean으로 모임장 구분 |
| `GroupAddRequest` | 가입 신청. `RequestStatus` = PENDING/ACCEPT/REJECT |
| `GroupService` | 12개 주요 메서드 — 생성/수정/멤버 관리/가입 승인/리더 위임/내보내기/탈퇴/해체 |

## 도메인 규칙

- **리더 권한**: 생성자가 자동 leader. 모임명·설명 수정, 가입 승인/거절, 멤버 내보내기, 리더 위임, 해체는 leader 전용(`ONLY_GROUP_LEADER`).
- **가입 플로우(신청형)**: 사용자 `createGroupAddRequest` → PENDING → leader `updateAddRequestStatus` → ACCEPT/REJECT. PENDING 중복 신청 금지(`ALREADY_GROUP_REQUEST`).
- **가입 플로우(초대 코드형)**: `createGroupMember`에서 코드 검증(`CODE_NOT_MATCH`). 이미 멤버면 코드만 검증하고 재가입 차단.
- **리더는 탈퇴/내보내기 불가**: 위임 먼저 필요.
- **코드 생성**: 12자리 UUID 기반, 충돌 시 최대 5회 재시도.
- **해체 순서**: `walletService.settleAllMembersShare()` → **잔액 및 Lot 검증** → `lotRepository.deleteByWalletId()` → `balanceRepository.deleteByWalletId()` → `groupMemberRepository.deleteByGroupId()` → `walletRepository.deleteById()` → `entityManager.flush()/clear()` → `groupRepository.delete()`. 순서 바꾸면 정합성 깨짐.
- **탈퇴/내보내기 시 공금 환급**: `walletService.settleShareToIndividual()` 호출.
- **멤버 조회 권한**: 자기 모임 멤버만 다른 멤버 목록 조회 가능.
- **알림**: 모든 주요 이벤트는 `afterCommit` 패턴으로 커밋 후 비동기 발송.

## 의존

- `domain.user.customer` (Customer만 모임 사용. 점주는 해당 없음)
- `domain.wallet.WalletService` (createGroupWallet/getGroupWallet/settle*)
- `domain.notification.NotificationService`
- `global.exception` (`ONLY_GROUP_LEADER`, `ONLY_GROUP_MEMBER`, `ALREADY_GROUP_MEMBER`, `ALREADY_GROUP_REQUEST`, `CODE_NOT_MATCH` 등)

## 엔드포인트

**`/api` 프리픽스 없음.** 컨트롤러 기본 경로는 `/groups`.

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| POST | `/groups` | 고객 | 모임 생성 |
| GET | `/groups` | 고객 | 모임 이름 검색 (모임장 이름 마스킹) |
| GET | `/groups/{groupId}` | 고객 | 모임 상세 |
| PATCH | `/groups/{groupId}` | leader | 이름·설명 수정 |
| DELETE | `/groups/{groupId}` | leader | 해체 |
| GET | `/groups/{groupId}/group-members` | 멤버 | 멤버 목록 |
| POST | `/groups/{groupId}/add-requests` | 고객 | 가입 신청 |
| GET | `/groups/{groupId}/add-requests` | leader | 가입 신청 목록 |
| PATCH | `/groups/{groupId}/add-requests` | leader | 승인/거절 |
| POST | `/groups/{groupId}/entrance` | 고객 | 초대 코드 입장 |
| PATCH | `/groups/{groupId}/group-leader` | leader | 리더 위임 |
| POST | `/groups/{groupId}/group-member` | leader | 멤버 강제 추가 |
| DELETE | `/groups/{groupId}/group-member` | leader/본인 | 내보내기 또는 탈퇴 |

## 주의사항

1. 리더 위임 없이 leader 탈퇴/내보내기 시도 → 금지. 프론트에서 흐름 가이드 필요.
2. 초대 코드 만료 정책이 없음. 한번 생성된 코드는 무기한 유효.
3. 해체 시 잔액 0 검증이 `settleAllMembersShare` 이후에 있는지 반드시 확인.
4. 알림 발송은 `afterCommit` — 커밋 실패 시 알림이 가지 않는다(의도된 동작).
5. 검색 결과에서 모임장 이름은 마스킹 처리 — 리포지토리 쿼리가 그 가정을 담고 있음.
