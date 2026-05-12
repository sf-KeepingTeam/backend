# domain/user

사용자 = **Customer(고객) + Owner(점주)**. 완전히 분리된 테이블·컨트롤러. 공통 DTO만 소량 공유.

## 하위 구조

```
user/
├── customer/
│   ├── controller/CustomerController
│   ├── model/Customer
│   ├── repository/CustomerRepository
│   └── service/CustomerService
├── owner/
│   ├── controller/OwnerController
│   ├── model/Owner
│   ├── repository/OwnerRepository
│   └── service/OwnerService
└── dto/           공통(UserProfile 등) + 각 역할 전용 DTO
```

## 핵심 클래스

| 파일 | 역할 |
|---|---|
| `Customer` | 모든 개인정보 **NOT NULL** (email, phoneNumber, birth, gender). `@SQLDelete` 자동 소프트 딜리트. Wallet/Transaction/GroupMember 매핑 |
| `Owner` | email/phoneNumber/birth/gender **nullable**. 프로필 수정 메서드 없음. `@SQLDelete` 없음 |
| `CustomerService` | 등록, 프로필 조회/수정, 이미지 업로드, `validCustomer` 조회 |
| `OwnerService` | 등록, 프로필 조회, 이미지 업로드 (**수정 없음**) |
| `UserProfile` | Customer/Owner 통합 응답 DTO. `UserRole`로 구분 |

## 도메인 규칙

- **역할 분리**: Customer/Owner는 서로 다른 테이블·컨트롤러. 경로 prefix 분리 (`/customers` / `/owners`).
- **소프트 딜리트 차이**:
  - Customer: `@SQLDelete` 자동.
  - Owner: 어노테이션 없음 — `deletedAt` 수동 설정 필요.
- **모든 조회 쿼리에 `DeletedAtIsNull`** 조건 필수.
- **nullable 정책**: Customer는 필수, Owner는 선택적 정보 허용.
- **중복 체크**: `existsByPhoneNumberAndDeletedAtIsNull` 메서드는 있으나 **register 메서드에서 호출 안 함** — 중복 가입 가능 상태. (주의사항 참고)
- **Customer만** Wallet·Group 연관.

## 의존

- `domain.auth` (`AuthProvider`, `Gender`, `UserRole` enums, `UserPrincipal`)
- `domain.wallet` (Customer ↔ Wallet 1:1)
- `domain.payment.transactions` (Customer 1:N Transaction)
- `domain.group` (Customer 1:N GroupMember, 조회만)
- `global.s3.ImageService`
- `global.exception`

## 엔드포인트

**`/api` 프리픽스 없음.**

| 메서드 | 경로 | 인증 | 비고 |
|---|---|---|---|
| GET | `/customers/me` | 고객 | 프로필 |
| PUT | `/customers/me` | 고객 | 이름·전화번호 수정 (PATCH 아님) |
| POST | `/customers/{customerId}/profile-image/upload` | 고객 | 이미지 업로드 (multipart) |
| GET | `/customers/me/groups` | 고객 | 내 모임 목록 |
| GET | `/owners/me` | 점주 | 프로필 |
| POST | `/owners/{ownerId}/profile-image/upload` | 점주 | 이미지 업로드 (multipart) |

## 주의사항

1. **Owner 프로필 수정 API 부재** — 이름/전화번호 변경 불가 상태. 추가 필요 시 `CustomerService.updateMyProfile` 패턴 참고. (고객 쪽 수정은 **PUT** `/customers/me` — 일반적 PATCH가 아니므로 주의)
2. **중복 가입 방지 구멍**: `existsByPhoneNumberAndDeletedAtIsNull`이 존재하나 register 경로에서 호출 안 됨. 같은 번호 재등록 가능. 추가 검증 필요 판단.
3. **에러 코드 불일치**: `OwnerService.getMyProfile`는 `BAD_REQUEST`, `CustomerService`는 `USER_NOT_FOUND`. 통일 검토.
4. Owner에 `@SQLDelete` 없음 — 소프트 딜리트 수동 구현. 서비스 레벨에서 `deletedAt` 세팅 잊으면 즉시 물리 삭제 또는 미삭제 상태로 남을 수 있음.
5. `UserRole` enum으로 Customer/Owner 혼용 함수 분기 — role 체크 누락 시 혼동.
6. 회원가입 시 `profileUrl`을 `imgUrl`에 그대로 대입 — 카드 등 다른 컨텍스트에서 imgUrl을 쓸 때 URL 스킴/경로 확인.
