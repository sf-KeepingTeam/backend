# QR Service MSA 분리 작업 기록

## 목차
1. [개요](#개요)
2. [아키텍처 변경](#아키텍처-변경)
3. [작업 내역](#작업-내역)
4. [로컬 테스트 가이드](#로컬-테스트-가이드)
5. [트러블슈팅](#트러블슈팅)
6. [용어 설명](#용어-설명)

---

## 개요

### 목적
모놀리식 아키텍처에서 **QR 결제 기능**을 별도의 마이크로서비스(QR Service)로 분리

### 변경 전
```
┌─────────────────────────────────┐
│           Monolith              │
│  - 사용자 관리                   │
│  - 포인트 충전 (Toss)            │
│  - QR 결제 ← 분리 대상           │
│  - 매장/메뉴 관리                │
│  - 환불 처리                     │
└─────────────────────────────────┘
```

### 변경 후
```
┌─────────────────────┐     ┌─────────────────────┐
│      Monolith       │     │     QR Service      │
│  - 사용자 관리       │     │  - QR 토큰 관리     │
│  - 포인트 충전       │◄────│  - 결제 의도        │
│  - 매장/메뉴 관리    │     │  - 멱등성 처리      │
│  - 환불 처리         │     │  - JWT 직접 검증    │
│  - 내부 API          │     │                     │
│    (/internal/*)    │     │                     │
└─────────────────────┘     └─────────────────────┘
        :8080                       :8082
```

---

## 아키텍처 변경

### 데이터베이스 분리

| 서비스 | 데이터베이스 | 저장소 |
|--------|-------------|--------|
| Monolith | `keeping` (MySQL) | 사용자, 매장, 메뉴, 지갑, 거래내역 |
| QR Service | `payment_service` (MySQL) | PaymentIntent, IdempotencyKey |
| QR Service | Redis | QR 토큰 (TTL 10초) |

### 인증 방식 변경

**변경 전**: Nginx → Monolith(인증) → Nginx → QR Service
**변경 후**: Nginx → QR Service (JWT 직접 검증)

QR Service가 자체적으로 JWT를 검증하므로 모놀리스를 거치지 않음

### 알림 처리 변경

**변경 전**: Saga 패턴 (비동기)
**변경 후**: 동기 처리 (NotificationClient → Monolith Internal API)

점주가 결제 완료를 즉시 알 수 있도록 동기 방식으로 변경

---

## 작업 내역

### 1. QR Service에서 삭제한 패키지 (24개 파일)

| 패키지 | 삭제 이유 |
|--------|----------|
| `saga/` (10개) | 알림이 동기로 변경되어 미사용 |
| `gateway/` (8개) | Toss 결제는 모놀리스 담당 |
| `toss/` (6개) | Toss 결제는 모놀리스 담당 |

### 2. 모놀리스에서 삭제한 패키지 (17개 파일)

| 패키지 | 삭제 이유 |
|--------|----------|
| `payment/intent/` (15개) | QR Service로 완전 이관 |
| `payment/funds/` (2개) | PaymentIntent 전용, 미사용 |

### 3. 수정된 파일

| 파일 | 변경 내용 |
|------|----------|
| 모놀리스 `SecurityConfig.java` | QR 경로 3개 제거 (`/cpqr/*`, `/payments/*/approve`) |
| 모놀리스 `PaymentRefundService.java` | 미사용 import 제거 |
| QR `ErrorCode.java` | 미사용 게이트웨이 에러코드 3개 제거 |
| QR `SchedulerConfig.java` | Saga 관련 주석 정리 |
| QR `application.yml` | Toss 설정 제거, JWT/DB 설정 통일 |

### 4. 빌드 검증

```bash
# 모놀리스
./gradlew :compileJava  # ✅ 성공

# QR Service
./gradlew :services:qr-service:compileJava  # ✅ 성공
```

---

## 로컬 테스트 가이드

### 사전 요구사항
- Docker Desktop
- JDK 17+
- Gradle

### 실행 순서

#### 1단계: Docker로 MySQL + Redis 실행
```bash
cd C:\keeping\backend
docker compose -f docker-compose.yml up -d mysql redis
```

#### 2단계: payment_service DB 생성
```bash
docker exec -it keeping-mysql mysql -u root -p1234 -e "CREATE DATABASE IF NOT EXISTS payment_service CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

#### 3단계: 모놀리스 실행 (터미널 1)
```bash
cd C:\keeping\backend
.\gradlew bootRun
```

#### 4단계: QR Service 실행 (터미널 2)
```bash
cd C:\keeping\backend
.\gradlew :services:qr-service:bootRun
```

#### 5단계: 헬스체크
```bash
curl http://localhost:8080/actuator/health  # 모놀리스
curl http://localhost:8082/actuator/health  # QR Service
```

### 포트 정리

| 서비스 | 포트 |
|--------|------|
| Monolith | 8080 |
| QR Service | 8082 |
| MySQL | 3306 |
| Redis | 6379 |

### 설정 파일 위치

| 서비스 | 설정 파일 |
|--------|----------|
| Monolith | `src/main/resources/application-local.yml` |
| QR Service | `services/qr-service/src/main/resources/application.yml` |

---

## 트러블슈팅

### 1. MySQL 클라이언트를 찾을 수 없음

**증상**:
```
mysql : 'mysql' 용어가 cmdlet, 함수, 스크립트 파일 또는 실행할 수 있는 프로그램 이름으로 인식되지 않습니다.
```

**원인**: MySQL 클라이언트가 PATH에 등록되지 않음

**해결**: Docker 컨테이너 내부에서 실행
```bash
docker exec -it keeping-mysql mysql -u root -p1234 -e "SQL문"
```

---

### 2. Docker Desktop WSL 오류

**증상**:
```
There was a problem with WSL
An error occurred while running a WSL command...
wsl: localhost 릴레이 프로세스를 시작하지 못했습니다.
```

**원인**: WSL (Windows Subsystem for Linux) 가상화 문제

**해결**:
```bash
# PowerShell 관리자 권한으로 실행
wsl --shutdown
# 10초 대기 후 Docker Desktop 재실행

# 그래도 안 되면
wsl --update
# 컴퓨터 재부팅
```

---

### 3. 포트 3306 이미 사용 중

**증상**:
```
Error response from daemon: ports are not available: exposing port TCP 0.0.0.0:3306 -> 127.0.0.1:0: listen tcp 0.0.0.0:3306: bind: Only one usage of each socket address...
```

**원인**: 로컬 MySQL이 이미 3306 포트 사용 중

**해결**:
1. Windows 서비스에서 MySQL 중지
   - `Win + R` → `services.msc`
   - MySQL 찾아서 중지
2. Docker 재실행
   ```bash
   docker compose -f docker-compose.yml up -d mysql redis
   ```

---

### 4. MySQL 컨테이너 연결 실패

**증상**:
```
ERROR 2002 (HY000): Can't connect to local MySQL server through socket '/var/run/mysqld/mysqld.sock' (2)
```

**원인**: MySQL 컨테이너가 아직 초기화 중

**해결**: 30초~1분 대기 후 재시도
```bash
# 상태 확인 (healthy 상태여야 함)
docker ps

# healthy 확인 후 재시도
docker exec -it keeping-mysql mysql -u root -p1234 -e "SHOW DATABASES;"
```

---

### 5. MySQL 비밀번호 불일치 (Access Denied)

**증상**:
```
Access denied for user 'root'@'172.18.0.1' (using password: YES)
```

**원인**: Docker MySQL 비밀번호와 application.yml 비밀번호 불일치

| 설정 | 파일 | 비밀번호 |
|------|------|---------|
| Docker MySQL | docker-compose.yml | `1234` |
| Monolith | application-local.yml | 확인 필요 |
| QR Service | application.yml | 확인 필요 |

**해결**: 모든 설정 파일의 비밀번호를 Docker MySQL과 일치시킴
```yaml
# application-local.yml, application.yml
password: 1234
```

---

### 6. 데이터베이스 미존재

**증상**:
```
Unknown database 'keeping'
```

**원인**: Docker MySQL에 데이터베이스가 생성되지 않음

**해결**: 필요한 DB 수동 생성
```bash
# keeping DB (모놀리스용)
docker exec -it keeping-mysql mysql -u root -p1234 -e "CREATE DATABASE IF NOT EXISTS keeping CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"

# payment_service DB (QR Service용)
docker exec -it keeping-mysql mysql -u root -p1234 -e "CREATE DATABASE IF NOT EXISTS payment_service CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
```

---

## 용어 설명

### WSL (Windows Subsystem for Linux)
Windows에서 Linux를 실행할 수 있게 해주는 호환성 계층. Docker Desktop on Windows는 WSL2를 사용하여 Linux 컨테이너를 실행함.

### Docker Desktop
Windows/Mac에서 Docker를 사용할 수 있게 해주는 애플리케이션. 내부적으로 WSL2(Windows) 또는 HyperKit(Mac)을 사용.

### MSA (Microservices Architecture)
하나의 큰 애플리케이션을 작은 서비스들로 분리하는 아키텍처. 각 서비스는 독립적으로 배포/확장 가능.

### ACL (Anti-Corruption Layer)
마이크로서비스 간 통신 시 외부 서비스의 데이터 형식을 내부 도메인 모델로 변환하는 계층. QR Service의 `acl/` 패키지가 이 역할 수행.

### Saga 패턴
분산 트랜잭션을 관리하는 패턴. 각 서비스의 로컬 트랜잭션을 순차적으로 실행하고, 실패 시 보상 트랜잭션 실행. (현재 미사용 - 동기 방식으로 변경됨)

### 멱등성 (Idempotency)
같은 요청을 여러 번 보내도 결과가 동일한 성질. 결제 시스템에서 중복 결제 방지에 필수.

---

## 로컬 테스트 검증 결과

### 2026-02-12 검증 완료

| 항목 | 상태 | 비고 |
|------|------|------|
| Docker MySQL 실행 | ✅ | port 3306 |
| Docker Redis 실행 | ✅ | port 6379 |
| keeping DB 생성 | ✅ | 모놀리스용 |
| payment_service DB 생성 | ✅ | QR Service용 |
| Monolith 실행 | ✅ | port 8080 |
| QR Service 실행 | ✅ | port 8082 |
| Monolith 헬스체크 | ✅ | HTTP 200 |
| QR Service 헬스체크 | ✅ | HTTP 200 |

---

## 변경 이력

| 날짜 | 작업 내용 |
|------|----------|
| 2026-02-12 | QR Service 분리, 불필요 코드 삭제, 로컬 테스트 환경 구성, 헬스체크 검증 완료 |
