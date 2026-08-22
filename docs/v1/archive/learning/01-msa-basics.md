# 01. MSA 기초 개념 (완전 초보자용)

## MSA가 뭐야?

### 모놀리식 (Monolithic) = 현재 우리 구조
```
하나의 Spring Boot 앱에 모든 기능이 들어있음

keeping-backend/
└── src/main/java/
    └── com/ssafy/keeping/
        ├── domain/
        │   ├── customer/    ← 고객 관리
        │   ├── wallet/      ← 지갑 관리
        │   ├── payment/     ← 결제 처리
        │   │   ├── qr/      ← QR 생성
        │   │   └── intent/  ← 결제 의도
        │   └── store/       ← 가게 관리
        └── KeepingApplication.java  ← 하나의 진입점
```

**장점**: 개발 쉬움, 배포 쉬움
**단점**: 하나가 느리면 전체가 느려짐 (우리가 테스트에서 본 문제!)

---

### MSA (Microservice Architecture) = 목표 구조
```
여러 개의 독립된 Spring Boot 앱으로 분리

[QR-Payment 서비스]     [Wallet 서비스]     [Customer 서비스]
    Spring Boot            Spring Boot          Spring Boot
    포트: 8081             포트: 8082           포트: 8080
       ↓                      ↓                    ↓
    Redis                   MySQL                MySQL
```

**장점**: 하나가 느려도 다른 건 정상 작동
**단점**: 서비스 간 통신 필요, 복잡함

---

## 왜 우리가 MSA가 필요해?

### 부하 테스트에서 발견한 문제

```
[테스트 결과]
QR 단독 테스트:     p95 = 25ms    ✅ 빠름
Wallet 부하 중 QR:  p95 = 756ms   ❌ 30배 느려짐!
```

**원인**: 모놀리식에서는 모든 요청이 같은 Thread Pool을 공유
```
                    ┌─────────────────────────────┐
                    │      Thread Pool (200개)     │
                    │  ┌───┐ ┌───┐ ┌───┐ ┌───┐   │
QR 요청 ──────────► │  │ T │ │ T │ │ T │ │ T │   │ ◄──── Wallet 요청
                    │  └───┘ └───┘ └───┘ └───┘   │
                    │    ↑       ↑       ↑       │
                    │    모두 경쟁해서 기다림      │
                    └─────────────────────────────┘
```

Wallet 요청이 Thread를 많이 쓰면 → QR도 기다려야 함

### MSA로 분리하면?
```
[QR 서비스]                    [Wallet 서비스]
┌──────────────────┐          ┌──────────────────┐
│ Thread Pool (50) │          │ Thread Pool (50) │
│   QR 전용!       │          │   Wallet 전용!   │
└──────────────────┘          └──────────────────┘
        ↓                              ↓
   독립 작동!                     부하 받아도
                                 QR에 영향 없음
```

---

## API Gateway가 뭐야?

### 문제 상황
```
클라이언트(앱)에서 여러 서비스를 어떻게 호출해?

[모놀리식]
앱 → http://server.com/api/qr       (서버 1개니까 간단)
앱 → http://server.com/api/wallet

[MSA]
앱 → http://qr-server.com/api/qr        ← QR 서버
앱 → http://wallet-server.com/api/wallet ← Wallet 서버
앱 → http://payment-server.com/api/...   ← Payment 서버

앱이 서버 주소를 다 알아야 해?  → 복잡!
서버 주소 바뀌면?              → 앱 업데이트 필요!
```

### 해결: API Gateway
```
                        ┌─────────────────────┐
                        │    API Gateway      │
                        │  (문지기/교통경찰)   │
앱 → http://gateway/    │                     │
    /api/qr        ────►│ → QR 서비스로 전달   │
    /api/wallet    ────►│ → Wallet 서비스로    │
    /api/payment   ────►│ → Payment 서비스로   │
                        └─────────────────────┘
```

**API Gateway 역할**:
1. **라우팅**: URL 보고 적절한 서비스로 전달
2. **로드밸런싱**: 같은 서비스 여러 개 있으면 분배
3. **인증**: 로그인 시 발급된 accessToken(JWT) 검증
   - 우리 구현: Nginx가 `Authorization` 헤더를 서비스로 전달
   - 실제 JWT 검증은 각 서비스의 Spring Security에서 수행
   - Nginx는 헤더를 "전달"만 하고, 검증은 백엔드가 담당
4. **Canary 배포**: 트래픽 일부만 새 버전으로

---

## Nginx가 뭐야?

### Nginx = 웹 서버 + API Gateway
```
아파치(Apache)처럼 웹 서버인데, 더 빠르고 가벼움
우리는 이걸 API Gateway로 사용
```

### 무중단 Nginx란?

Nginx 설정을 변경할 때 서비스를 중단하지 않는 방법입니다.

**`nginx -s reload` 명령:**
```bash
docker compose exec nginx nginx -s reload
```
- 새 설정 파일을 로드
- 기존 연결은 유지하면서 새 worker 프로세스 시작
- 기존 요청 처리 완료 후 이전 worker 종료

**우리 프로젝트:**
Docker 환경에서 `nginx -s reload`로 무중단 설정 변경 가능

### Nginx 설정 예시
```nginx
# /etc/nginx/nginx.conf

http {
    # "upstream" = 백엔드 서버 그룹 정의
    upstream qr-service {
        server qr-server:8081;    # QR 서비스 주소
    }

    upstream monolith {
        server main-server:8080;  # 기존 모놀리스 주소
    }

    server {
        listen 80;  # 80번 포트에서 대기

        # /api/qr로 오는 요청 → qr-service로 전달
        location /api/qr {
            proxy_pass http://qr-service;
        }

        # 나머지 → 기존 모놀리스로
        location / {
            proxy_pass http://monolith;
        }
    }
}
```

### 동작 원리
```
1. 클라이언트가 http://gateway/api/qr 요청
2. Nginx가 URL 확인: "/api/qr" 이네?
3. location /api/qr 규칙 적용
4. proxy_pass로 http://qr-service에 전달
5. QR 서비스 응답을 클라이언트에 반환
```

### Gateway를 통한 호출 vs 직접 호출

**Q: `http://gateway:80/api/qr` vs `http://qr-service:8081/api/qr` 차이?**

둘 다 같은 서비스에 도달하지만:

```
[외부 클라이언트]
     │
     │  http://gateway:80/api/qr
     ▼
┌──────────┐
│  Nginx   │ ← 단일 진입점 (외부에서 여기만 알면 됨)
│  :80     │
└────┬─────┘
     │ proxy_pass
     ▼
┌──────────┐
│QR Service│ ← 내부 서비스 (외부에서 직접 접근 불가)
│  :8081   │
└──────────┘
```

| 구분 | Gateway 경유 | 직접 호출 |
|------|-------------|-----------|
| 외부 클라이언트 | ✅ 가능 | ❌ 불가 (내부망) |
| 서비스 주소 변경 시 | Gateway 설정만 수정 | 모든 클라이언트 수정 |
| 인증/로깅 | 중앙 처리 | 각 서비스에서 처리 |

**결론**: 외부는 Gateway로, 내부 서비스 간은 직접 호출 가능

---

## Strangler Fig Pattern이 뭐야?

### 이름의 유래
```
"교살자 무화과" - 다른 나무를 감싸며 자라다가
결국 원래 나무를 대체하는 식물

모놀리스(원래 나무)를 MSA(무화과)가 점점 감싸서 대체
```

### 패턴 설명 (그림으로)

**Phase 1: 모놀리스만 있음**
```
┌──────────────────────────────────┐
│           모놀리스               │
│  ┌─────┐ ┌─────┐ ┌─────┐       │
│  │ QR  │ │Wallet│ │Auth │ ...   │
│  └─────┘ └─────┘ └─────┘       │
└──────────────────────────────────┘
```

**Phase 2: Gateway 추가, 새 서비스 배포 (아직 트래픽 0%)**
```
                    ┌──────────────┐
                    │   Gateway    │
                    └──────┬───────┘
            ┌──────────────┼──────────────┐
            ▼              ▼              ▼
    ┌──────────────┐  ┌─────────┐  (트래픽 0%)
    │   모놀리스    │  │QR 서비스│ ◄── 새로 만듦
    │ (기존 100%)  │  │ (대기중) │
    └──────────────┘  └─────────┘
```

**Phase 3: 트래픽 점진 이동**
```
                    ┌──────────────┐
                    │   Gateway    │
                    │  5% → 새 QR  │
                    │ 95% → 모놀리스│
                    └──────┬───────┘
            ┌──────────────┼──────────────┐
            ▼              ▼
    ┌──────────────┐  ┌─────────┐
    │   모놀리스    │  │QR 서비스│
    │   (95%)      │  │  (5%)   │
    └──────────────┘  └─────────┘
```

**Phase 4: 완전 이전**
```
                    ┌──────────────┐
                    │   Gateway    │
                    │ 100% → QR   │
                    └──────┬───────┘
            ┌──────────────┼──────────────┐
            ▼              ▼
    ┌──────────────┐  ┌─────────┐
    │   모놀리스    │  │QR 서비스│
    │ (QR 코드     │  │ (100%) │
    │  삭제 가능)  │  └─────────┘
    └──────────────┘
```

### 장점
1. **무중단**: 서비스 안 끊김
2. **안전**: 문제 생기면 바로 롤백
3. **점진적**: 한 번에 다 안 바꿔도 됨

---

## Canary 배포가 뭐야?

### 이름의 유래
```
옛날 광부들이 카나리아 새를 갱도에 먼저 보냄
→ 새가 죽으면 가스 있음 → 위험!
→ 새가 살면 안전 → 들어가도 됨

새 버전을 소수에게만 먼저 적용
→ 문제 생기면 소수만 영향 → 롤백
→ 문제 없으면 점점 확대
```

### 배포 단계
```
Day 1: 5%만 새 서비스로
┌────────────────────────────────┐
│ 사용자 100명 중 5명만 새 서비스 │
│ → 에러율 확인, 응답시간 확인    │
│ → 문제 없으면 다음 단계        │
└────────────────────────────────┘

Day 3: 25%로 확대
┌────────────────────────────────┐
│ 사용자 100명 중 25명           │
│ → 더 많은 케이스 테스트        │
└────────────────────────────────┘

Day 5: 50%로 확대
...

Day 7: 100% (완전 이전)
```

### Nginx로 Canary 구현
```nginx
# split_clients = 랜덤하게 분배
split_clients "${request_id}" $backend {
    5%   qr-service;   # 5%는 새 서비스로
    95%  monolith;     # 95%는 기존으로
}

location /api/qr {
    proxy_pass http://$backend;  # 위에서 정한 대로 분배
}
```

---

## ACL (Anti-Corruption Layer)가 뭐야?

### 문제 상황
```
QR 서비스가 Wallet 정보가 필요해!

[QR 서비스]                      [모놀리스의 Wallet]
    │                                   │
    │  walletId로 잔액 조회 필요!        │
    │ ─────────────────────────────────►│
    │                                   │
    │  어떻게 호출하지?                  │
```

### 해결: WalletClient (ACL)
```java
// QR 서비스 안에 만드는 클래스
@Component
public class WalletClient {

    private final RestTemplate restTemplate;

    // 모놀리스의 Wallet API를 HTTP로 호출
    public BigDecimal getBalance(Long walletId) {
        String url = "http://monolith:8080/api/wallets/" + walletId + "/balance";
        return restTemplate.getForObject(url, BigDecimal.class);
    }
}
```

### ACL의 역할
```
[QR 서비스]
    │
    ├── QrController
    │       │
    │       ▼
    ├── QrService
    │       │
    │       │ walletClient.getBalance(walletId)
    │       ▼
    └── WalletClient ◄─── ACL (Anti-Corruption Layer)
            │
            │ HTTP 요청
            ▼
       [모놀리스의 Wallet API]
```

**왜 "Anti-Corruption"이야?**
- 외부 시스템(모놀리스)의 변경이 우리 코드를 "오염"시키지 않게
- WalletClient만 수정하면 됨, QrService는 그대로

---

## 우리 프로젝트 실제 구현 현황 (Q&A)

> 이 문서는 **개념 설명**이고, 실제 구현은 조금 다릅니다.
> 아래는 실제로 어떻게 구현했는지 정리입니다.

### Q: Strangler Fig 패턴에서 점진적 전환을 했나?

**아니요, 한 번에 분리했습니다.**

문서에서 설명한 점진적 방식:
```
Day 1: QR 생성만 분리 → Day 2: QR 검증 분리 → Day 3: 전체 분리
```

실제 우리 프로젝트:
```
한 번에 QR + Payment 서비스 전체를 분리 배포
```

### Q: Canary 배포 (5% → 25% → 100%)를 거쳤나?

**아니요, 100% 바로 전환했습니다.**

문서에서 설명한 방식:
```nginx
split_clients "${request_id}" $qr_backend {
    5%   qr-payment;    # 5%만 새 서비스로
    95%  monolith;      # 나머지는 기존으로
}
```

실제 우리 nginx.conf:
```nginx
# split_clients 없음!
location /cpqr {
    proxy_pass http://payment;  # 100% payment로 바로 전환
}
```

**왜?** 테스트 환경이라 빠르게 진행. 프로덕션에서는 Canary 배포 권장!

### Q: split_clients 설정을 했나?

**아니요, 사용하지 않았습니다.**

현재 `gateway/nginx.conf`:
- `/api/qr` → monolith (테스트용)
- `/cpqr/{id}/initiate` → payment (100%)
- `/payments/{id}/approve` → payment (100%)

Canary 배포를 하려면 06-canary-deployment.md 참고!

---

## WalletClient는 언제/어떻게 호출되나?

### Q: "FundsService.capture() → WalletClient.capture() 이게 어떻게 넘어가?"

**답변: Spring의 의존성 주입(DI)으로 연결됩니다.**

```java
// FundsService.java
@Service
@RequiredArgsConstructor  // Lombok이 생성자 자동 생성
public class FundsService {

    private final WalletClient walletClient;  // 여기서 주입받음!

    public FundsResponse capture(...) {
        // walletClient를 사용
        return walletClient.capture(request);  // HTTP로 모놀리스 호출
    }
}

// WalletClient.java
@Component  // Spring Bean으로 등록
public class WalletClient {

    private final RestTemplate restTemplate;  // HTTP 클라이언트

    @Value("${monolith.url}")  // application.yml에서 URL 읽음
    private String monolithUrl;

    public FundsResponse capture(FundsCaptureRequest request) {
        String url = monolithUrl + "/internal/wallets/..." ;
        return restTemplate.exchange(url, POST, ...);  // HTTP POST!
    }
}
```

**핵심: 같은 JVM 내 메서드 호출 → HTTP 요청으로 변환!**

### 호출 흐름 (상세)

```
[사용자] POST /payments/{id}/approve (결제 승인)
    │
    ▼
PaymentApprovalController.approve()   (EC2-A, Payment Service)
    │
    ▼
PaymentIntentService.approve()        (EC2-A, Payment Service)
    │
    ▼
FundsService.capture()                (EC2-A, Payment Service)
    │
    │  walletClient.capture(request)  ← 메서드 호출
    ▼
WalletClient.capture()                (EC2-A, Payment Service)
    │
    │  restTemplate.exchange(url, POST, ...)  ← HTTP 요청!
    │
    ▼  ════════════ 네트워크 경계 ════════════
    │
[모놀리스] POST /internal/wallets/{walletId}/stores/{storeId}/capture
    │                                 (EC2-B, Monolith)
    ▼
WalletInternalController.capture()    (EC2-B, 모놀리스)
    │
    ▼
WalletService.capture()               (EC2-B, 모놀리스)
    │
    ▼
MySQL에 잔액 차감                      (EC2-B)
```

### 코드 경로

```
services/payment-service/src/main/java/com/ssafy/keeping/payment/
├── domain/intent/controller/
│   └── PaymentApprovalController.java  ← 1. API 진입점
├── domain/intent/service/
│   ├── PaymentIntentService.java       ← 2. approve() 메서드
│   └── FundsService.java               ← 3. capture() 메서드
└── acl/
    └── WalletClient.java               ← 4. 모놀리스 API 호출
```

---

## walletId 요청 흐름 전체

### 전체 흐름도

```
1. [사용자 앱] QR 생성 요청
   POST /api/qr
   Body: { walletId: 123, customerId: 456, mode: "CPQR" }
        │
        ▼
2. [QR-Payment Service] Redis에 QR 토큰 저장
   Key: "qr:abc-def-123"
   Value: {
     tokenId: "abc-def-123",
     walletId: 123,          ← walletId 저장!
     customerId: 456,
     mode: "CPQR",
     ttl: 5초
   }
        │
        ▼
3. [점주 앱] QR 스캔 → 결제 의도 생성
   POST /cpqr/{qrTokenId}/initiate
   Body: { items: [...], totalAmount: 10000 }
        │
        ▼
4. [Payment Service] QR 토큰에서 walletId 추출
   - QrPaymentClient로 QR 토큰 조회
   - walletId, customerId 추출
   - PaymentIntent 엔티티 생성 (walletId 저장)
        │
        ▼
5. [사용자 앱] 결제 승인
   POST /payments/{id}/approve
   Body: { pin: "1234" }
        │
        ▼
6. [Payment Service] 잔액 차감
   - PaymentIntent에서 walletId 조회
   - WalletClient.capture(walletId, storeId, amount)
        │
        ▼
7. [모놀리스] 실제 잔액 차감
   POST /internal/wallets/{walletId}/stores/{storeId}/capture
   - 지갑 잔액 차감
   - 거래 내역 생성
```

### walletId 전달 요약

| 단계 | 컴포넌트 | walletId 출처 |
|------|----------|---------------|
| 1 | QR 생성 | 사용자 앱이 전송 |
| 2 | Redis | QR 토큰에 저장 |
| 3-4 | 결제 의도 생성 | QR 토큰에서 추출 |
| 5-6 | 결제 승인 | PaymentIntent에서 조회 |
| 7 | 잔액 차감 | WalletClient 파라미터 |

**핵심**: walletId는 QR 생성 시 저장 → 결제 의도 → 승인까지 계속 전달됨

---

## 성능 테스트 결과 (실제)

> 자세한 내용: 12-server-separation-final-report.md

| 환경 | QR 단독 p(95) | Mixed Load p(95) | 성능 저하 |
|------|---------------|------------------|----------|
| 모놀리식 (단일 서버) | 19.42ms | 81.61ms | **4.2배** |
| MSA (단일 서버) | 24.72ms | 88.99ms | **3.6배** |
| **서버 분리 (EC2 2대)** | 25ms | **43.56ms** | **1.74배** |

**결론**: MSA 코드 분리만으로는 부족, **서버 분리**로 진정한 격리 달성!

---

## 다음 단계

이제 기본 개념을 알았으니:
1. [02-project-structure.md](./02-project-structure.md) - 프로젝트 구조 만들기
2. [03-qr-payment-service.md](./03-qr-payment-service.md) - QR 서비스 코드 작성
3. [04-nginx-gateway.md](./04-nginx-gateway.md) - Nginx 설정
4. [05-docker-compose.md](./05-docker-compose.md) - Docker로 실행
5. [06-canary-deployment.md](./06-canary-deployment.md) - Canary 배포
