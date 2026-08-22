# AWS 백엔드 배포 & CI/CD 구축 가이드 (실서비스 버전)

> 작성일: 2026-03-18
> 목표: 도메인 + HTTPS + CI/CD 완비된 실서비스 배포
> 구성: Nginx Gateway + Monolith + QR Service (2대 EC2)

---

# Part 1: 알아야 할 핵심 개념

## 1. AWS 핵심 서비스 개념

### EC2 (Elastic Compute Cloud)
```
EC2 = 클라우드 가상 서버 (컴퓨터 한 대 빌리는 것)

내 PC처럼 SSH로 접속해서 뭐든 설치하고 실행 가능
- 운영체제 선택 (Amazon Linux, Ubuntu 등)
- 사양 선택 (CPU, RAM)
- 24시간 돌아감
```

**인스턴스 타입:**

| 타입 | vCPU | RAM | 용도 | 월 비용 |
|------|------|-----|------|---------|
| t2.micro | 1 | 1GB | 프리티어, 아주 가벼운 작업 | 무료 |
| t3.small | 2 | 2GB | 소규모 서비스 | ~$18 |
| t3.medium | 2 | 4GB | 중규모 서비스 | ~$35 |

### Elastic IP
```
Elastic IP = 고정 IP 주소

일반 EC2 IP는 서버 재시작하면 바뀜
Elastic IP는 서버 재시작해도 안 바뀜

사용 중이면 무료, 안 쓰면서 보유만 하면 과금
```

### 보안 그룹 (Security Group)
```
보안 그룹 = 방화벽 규칙

어떤 포트를, 어떤 IP에서 접근 허용할지 설정

예시:
┌──────────┬──────────┬─────────────────┐
│ 포트     │ 용도      │ 허용 IP         │
├──────────┼──────────┼─────────────────┤
│ 22       │ SSH 접속  │ 내 IP만         │
│ 80       │ HTTP     │ 모든 IP         │
│ 443      │ HTTPS    │ 모든 IP         │
│ 3306     │ MySQL    │ 차단            │
└──────────┴──────────┴─────────────────┘
```

### VPC (Virtual Private Cloud)
```
VPC = 나만의 가상 네트워크 공간

AWS 안에서 내 서버들이 위치하는 격리된 네트워크
기본 VPC(Default VPC)가 이미 있어서 직접 만들 필요 없음
```

### Route 53
```
Route 53 = AWS DNS 서비스

도메인 이름 → IP 주소 연결
api.keeping.o-r.kr → 54.116.93.155

도메인 구매도 가능 (~$12/년)
외부에서 구매한 도메인도 연결 가능
```

---

## 2. 배포 관련 개념

### Nginx
```
Nginx = 웹서버 / 리버스 프록시

역할:
1. 클라이언트 요청을 받음 (80/443 포트)
2. 요청 URL에 따라 적절한 서비스로 전달
3. SSL 인증서 처리 (HTTPS)

흐름:
사용자 → Nginx(:443) → /api/* → Monolith(:8080)
                     → /api/qr/* → QR Service(:8082)
```

### SSL/TLS 인증서
```
SSL = HTTPS 암호화 통신

HTTP  → 암호화 안 됨 (비밀번호 노출 위험)
HTTPS → 암호화 됨 (안전)

Let's Encrypt: 무료 SSL 인증서 (3개월마다 갱신)
자동 갱신 스크립트로 관리
```

### Docker & Docker Compose
```
Docker = 애플리케이션을 컨테이너로 패키징
Docker Compose = 여러 컨테이너를 한 번에 관리

docker-compose.yml 하나로 앱 + DB + Redis 동시 실행
```

### CI/CD (GitHub Actions)
```
CI = 코드 푸시하면 자동으로 빌드
CD = 빌드 완료되면 자동으로 서버에 배포

1. main 브랜치에 Push
2. GitHub Actions가 자동 실행
3. Docker 이미지 빌드 & Push
4. EC2에 SSH 접속해서 배포
```

---

# Part 2: 최종 아키텍처

## 실서비스 구조 (도메인 분리형)

> **왜 도메인 분리?**
> - EC2 #1 (Monolith)이 죽어도 EC2 #2 (QR Service)에 접근 가능
> - 복구 트랜잭션 테스트 가능
> - 각 서버가 독립적으로 운영됨

```
                         [인터넷/사용자]
                               │
                               ▼
                        [Route 53 DNS]
                       /              \
           api.keeping.o-r.kr         qr.keeping.o-r.kr
                  │                       │
                  ▼                       ▼
           [Elastic IP]            [Elastic IP]
          54.116.93.155            15.165.139.178
                  │                       │
                  ▼                       ▼
┌─────────────────────────────┐  ┌─────────────────────────────┐
│   EC2 #1 - Monolith Server  │  │   EC2 #2 - QR Server        │
│        (t3.small)           │  │        (t3.small)           │
├─────────────────────────────┤  ├─────────────────────────────┤
│  ┌───────────────────────┐  │  │  ┌───────────────────────┐  │
│  │   Nginx (80/443)      │  │  │  │   Nginx (80/443)      │  │
│  │   SSL (Let's Encrypt) │  │  │  │   SSL (Let's Encrypt) │  │
│  └───────────────────────┘  │  │  └───────────────────────┘  │
│           │                 │  │           │                 │
│           ▼                 │  │           ▼                 │
│  ┌───────────────────────┐  │  │  ┌───────────────────────┐  │
│  │  Monolith (:8080)     │  │  │  │  QR Service (:8082)   │  │
│  │  Spring Boot + Docker │  │  │  │  Spring Boot + Docker │  │
│  └───────────────────────┘  │  │  └───────────────────────┘  │
│  ┌───────────┐ ┌──────────┐ │  │  ┌───────────┐ ┌──────────┐ │
│  │MySQL:3306 │ │Redis:6379│ │  │  │MySQL:3306 │ │Redis:6379│ │
│  │ssafy_db   │ │세션,캐시  │ │  │  │payment_db │ │QR토큰    │ │
│  └───────────┘ └──────────┘ │  │  └───────────┘ └──────────┘ │
│                             │  │                             │
│  Private: 172.31.11.13      │  │  Private: 172.31.11.167      │
└─────────────────────────────┘  └─────────────────────────────┘
                  │                       │
                  └───────────────────────┘
                     REST API (Private IP)
                   서버 간 내부 통신 (8080 ↔ 8082)
```

## IP 주소 정리

| 서버 | 도메인 | Elastic IP | Private IP | 용도 |
|------|--------|------------|------------|------|
| EC2 #1 | api.keeping.o-r.kr | 54.116.93.155 | 172.31.11.13 | Monolith API |
| EC2 #2 | qr.keeping.o-r.kr | 15.165.139.178 | 172.31.11.167 | QR/결제 API |

## 복구 트랜잭션 테스트 시나리오

```
1. EC2 #1 (Monolith) 서버 중지
2. qr.keeping.o-r.kr 으로 QR 결제 요청
3. QR Service: 결제 처리 → Monolith 동기화 실패 → 복구 트랜잭션 저장
4. EC2 #1 (Monolith) 서버 시작
5. QR Service: 복구 트랜잭션 재시도 → 성공
```

## 요청 흐름 예시

```
[Monolith API 요청] - api.keeping.o-r.kr
1. 사용자가 https://api.keeping.o-r.kr/api/auth/login 요청
2. Route 53: api.keeping.o-r.kr → 54.116.93.155 (EC2 #1)
3. EC2 #1 Nginx: HTTPS(443) → localhost:8080 전달
4. Monolith가 처리 후 응답

[QR/결제 API 요청] - qr.keeping.o-r.kr
1. 사용자가 https://qr.keeping.o-r.kr/api/qr/scan 요청
2. Route 53: qr.keeping.o-r.kr → 15.165.139.178 (EC2 #2)
3. EC2 #2 Nginx: HTTPS(443) → localhost:8082 전달
4. QR Service가 처리
5. (필요시) QR Service → Monolith(172.31.11.13:8080) 내부 통신
6. 응답 반환

[복구 시나리오] - Monolith 다운 시
1. 사용자가 https://qr.keeping.o-r.kr/api/qr/pay 요청
2. QR Service: 결제 처리 완료
3. QR Service → Monolith 동기화 시도 → 실패!
4. QR Service: 복구 트랜잭션 저장 (Redis/DB)
5. (나중에) Monolith 복구 후 재시도 → 성공
```

---

# Part 3: 실제 배포 단계

---

## ⚠️ 기존 설정 수정 (Step 3.6까지 완료한 경우)

> EC2 2대 + Elastic IP까지 완료했다면, 아래 사항만 수정하면 됩니다.

### 수정 1: EC2 #2 보안 그룹에 80, 443 포트 추가

> 기존에는 8082만 열었는데, 이제 Nginx를 설치하므로 80/443도 열어야 함

```
1. AWS Console > EC2 > Instances
2. "keeping-qr-service" 클릭
3. 하단 "Security" 탭 클릭
4. "Security groups" 옆 파란색 링크 클릭 (sg-xxxxx)
5. 하단 "Inbound rules" 탭 > "Edit inbound rules" 클릭
6. "Add rule" 버튼 2번 클릭해서 아래 규칙 추가:

   규칙 1:
   - Type: HTTP
   - Port: 80
   - Source: Anywhere-IPv4 (0.0.0.0/0)

   규칙 2:
   - Type: HTTPS
   - Port: 443
   - Source: Anywhere-IPv4 (0.0.0.0/0)

7. "Save rules" 클릭
```

### 수정 완료 후 EC2 #2 보안 그룹 상태

| Type | Port | Source | 설명 |
|------|------|--------|------|
| SSH | 22 | My IP | SSH 접속 |
| HTTP | 80 | 0.0.0.0/0 | Nginx HTTP |
| HTTPS | 443 | 0.0.0.0/0 | Nginx HTTPS |
| Custom TCP | 8082 | 172.31.11.13/32 | EC2 #1에서 내부 접근 |

### 수정 2: EC2 #1 보안 그룹에 8080 포트 추가

> QR Service → Monolith 내부 통신을 위해 필요

```
1. AWS Console > EC2 > Instances
2. "keeping-main" 클릭
3. 하단 "Security" 탭 > Security groups 링크 클릭
4. "Inbound rules" > "Edit inbound rules"
5. "Add rule" 클릭:

   - Type: Custom TCP
   - Port range: 8080
   - Source: Custom → EC2 #2의 Private IP/32 입력
     (예: 172.31.11.167/32)
   - Description: QR Service internal access

6. "Save rules" 클릭
```

> **EC2 #2의 Private IP 확인 방법:**
> EC2 > Instances > keeping-qr-service 클릭 > Private IPv4 address

---

## 배포 순서 요약

```
Step 1: AWS 계정 + MFA 설정 ✅ 완료
Step 2: EC2 2대 생성 ✅ 완료
Step 3: Elastic IP 연결 ✅ 완료
Step 3.5: 보안 그룹 수정 ← 위에서 완료!
Step 4: Docker 설치
Step 5: 도메인 구매 & Route 53 설정
Step 6: 프로젝트 배포 (Monolith, QR Service)
Step 7: Nginx + SSL 설정
Step 8: GitHub Actions CI/CD 설정
```

---

## Step 1: AWS 계정 준비

### 1.1 AWS 계정 로그인
- [ ] [AWS Console](https://console.aws.amazon.com/) 접속
- [ ] 계정 생성 또는 로그인

### 1.2 MFA 활성화 (필수!)
- [ ] 우측 상단 계정명 클릭 > `Security credentials`
- [ ] MFA > `Assign MFA device`
- [ ] `Authenticator app` 선택
- [ ] Google Authenticator 앱으로 QR 스캔
- [ ] 코드 2개 입력 후 완료

---

## Step 2: EC2 인스턴스 생성 (2대)

### 2.1 EC2 #1 (Main Server: Nginx + Monolith)

- [ ] EC2 > `Launch instances`

| 항목 | 값 |
|------|-----|
| Name | `keeping-main` |
| AMI | `Amazon Linux 2023` |
| Instance type | `t3.small` |
| Key pair | `Create new` → `keeping-key` (.pem 다운로드) |

### 2.2 보안 그룹 설정 (EC2 #1)

| Type | Port | Source | 설명 |
|------|------|--------|------|
| SSH | 22 | My IP | SSH 접속 |
| HTTP | 80 | 0.0.0.0/0 | HTTP (리다이렉트용) |
| HTTPS | 443 | 0.0.0.0/0 | HTTPS (메인) |
| Custom TCP | 8080 | My IP | 테스트용 (나중에 제거 가능) |

### 2.3 스토리지
- [ ] `20 GiB`, `gp3`

### 2.4 EC2 #2 (QR Service Server)

| 항목 | 값 |
|------|-----|
| Name | `keeping-qr-service` |
| AMI | `Amazon Linux 2023` |
| Instance type | `t3.small` |
| Key pair | 기존 `keeping-key` 선택 |

### 2.5 보안 그룹 설정 (EC2 #2)

| Type | Port | Source | 설명 |
|------|------|--------|------|
| SSH | 22 | My IP | SSH 접속 |
| HTTP | 80 | 0.0.0.0/0 | HTTP (리다이렉트용) |
| HTTPS | 443 | 0.0.0.0/0 | HTTPS (메인) |
| Custom TCP | 8082 | EC2 #1 Private IP | 내부 통신용 |

---

## Step 3: Elastic IP 할당 (고정 IP)

> Elastic IP = 서버 재시작해도 안 바뀌는 고정 IP

### 3.1 Elastic IP 페이지로 이동

```
1. AWS Console 왼쪽 상단 검색창에 "EC2" 입력
2. EC2 클릭
3. 왼쪽 메뉴에서 "Network & Security" 섹션 찾기
4. "Elastic IPs" 클릭
```

### 3.2 첫 번째 Elastic IP 생성 (Main Server용)

```
1. 오른쪽 상단 주황색 버튼 "Allocate Elastic IP address" 클릭
2. 설정은 건드리지 말고 그대로 두기
3. 맨 아래 "Allocate" 버튼 클릭
4. 초록색 성공 메시지 나오면 완료
5. 생성된 IP 주소 메모 (예: 3.35.123.456) → MAIN_SERVER_IP
```

### 3.3 두 번째 Elastic IP 생성 (QR Server용)

```
1. 다시 "Allocate Elastic IP address" 클릭
2. 그대로 "Allocate" 클릭
3. 생성된 IP 주소 메모 (예: 3.36.789.012) → QR_SERVER_IP
```

### 3.4 첫 번째 IP를 Main Server에 연결

```
1. 방금 만든 Elastic IP 목록에서 첫 번째 IP 체크박스 선택
2. 오른쪽 상단 "Actions" 드롭다운 클릭
3. "Associate Elastic IP address" 클릭
4. Resource type: "Instance" 선택 (기본값)
5. Instance: 검색창 클릭 → "keeping-main" 선택
6. "Associate" 버튼 클릭
```

### 3.5 두 번째 IP를 QR Server에 연결

```
1. 두 번째 Elastic IP 체크박스 선택
2. Actions > "Associate Elastic IP address"
3. Instance: "keeping-qr-service" 선택
4. "Associate" 클릭
```

### 3.6 IP 정보 기록하기

```bash
# 메모장에 적어두세요!
MAIN_SERVER_IP=54.116.93.155      # EC2 #1 (Nginx + Monolith)
QR_SERVER_IP=15.165.139.178        # EC2 #2 (QR Service)

# Private IP도 확인 (EC2 > Instances > 각 인스턴스 클릭 > Private IPv4 address)
MAIN_PRIVATE_IP=172.31.11.13      # EC2 #1 Private IP
QR_PRIVATE_IP=172.31.11.167       # EC2 #2 Private IP
```

### 3.7 EC2 #2 보안 그룹에 Private IP 추가

> EC2 #1 → EC2 #2 통신을 위해 Private IP 허용

```
1. EC2 > Instances > "keeping-qr-service" 클릭
2. 하단 탭에서 "Security" 탭 클릭
3. "Security groups" 옆에 파란색 링크 클릭 (sg-xxxxx)
4. 하단 "Inbound rules" 탭 클릭
5. "Edit inbound rules" 버튼 클릭
6. 8082 포트 규칙 찾기
7. Source를 EC2 #1의 Private IP로 변경: 172.31.11.13/32
8. "Save rules" 클릭
```

---

## Step 4: EC2 접속 및 Docker 설치

### 4.1 keeping-key.pem 파일 위치 확인

```
다운로드 폴더에 keeping-key.pem 파일이 있을 것임
예: C:\Users\{사용자명}\Downloads\keeping-key.pem

이 파일을 작업하기 편한 곳으로 이동 (예: C:\keys\keeping-key.pem)
```

### 4.2 PowerShell 열기

```
1. Windows 검색창에 "PowerShell" 입력
2. "Windows PowerShell" 클릭 (관리자 권한 아니어도 됨)
```

### 4.3 키 파일 권한 설정 (Windows)

```powershell
# 키 파일이 있는 폴더로 이동
cd C:\Users\{사용자명}\Downloads

# 또는 키 파일을 옮겼다면
cd C:\keys

# 키 파일 권한 설정 (이거 안 하면 SSH 접속 안 됨)
icacls keeping-key.pem /inheritance:r /grant:r "$($env:USERNAME):(R)"
```

### 4.4 Main Server (EC2 #1) SSH 접속

```powershell
# {MAIN_SERVER_IP}를 실제 IP로 변경!
ssh -i keeping-key.pem ec2-user@54.116.93.155

# 처음 접속하면 "Are you sure you want to continue connecting?" 나옴
# yes 입력하고 Enter
```

접속 성공하면 이런 화면:
```
   ,     #_
   ~\_  ####_        Amazon Linux 2023
  ~~  \_#####\
  ~~     \###|
  ~~       \#/ ___
   ~~       V~' '->
    ~~~         /
      ~~._.   _/
         _/ _/
       _/m/'
[ec2-user@ip-172-31-xx-xx ~]$
```

### 4.5 Docker 설치 (Main Server에서)

> 아래 명령어를 한 줄씩 복사해서 붙여넣기 (Ctrl+V 또는 마우스 우클릭)

```bash
# 1. 시스템 업데이트 (1-2분 소요)
sudo dnf update -y
```

```bash
# 2. Docker 설치
sudo dnf install -y docker
```

```bash
# 3. Docker 시작 & 자동 시작 설정
sudo systemctl start docker
sudo systemctl enable docker
```

```bash
# 4. 현재 사용자(ec2-user)를 docker 그룹에 추가
sudo usermod -aG docker $USER
```

```bash
# 5. Docker Compose 설치
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
```

```bash
# 6. 그룹 적용을 위해 재접속
exit
```

### 4.6 다시 Main Server 접속 & 설치 확인

```powershell
# 다시 SSH 접속
ssh -i keeping-key.pem ec2-user@54.116.93.155
```

```bash
# Docker 버전 확인 (버전 숫자 나오면 성공)
docker --version
# 출력 예: Docker version 25.0.0, build xxxxx

# Docker Compose 버전 확인
docker-compose --version
# 출력 예: Docker Compose version v2.24.0
```

### 4.7 스왑 메모리 추가 (Main Server에서)

> RAM이 부족할 때 디스크를 RAM처럼 사용하는 설정

```bash
# 2GB 스왑 파일 생성
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile

# 재부팅 후에도 유지되게 설정
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab

# 확인 (Swap: 2G 나오면 성공)
free -h
```

### 4.8 Git 설치 (Main Server에서)

```bash
sudo dnf install -y git

# 확인
git --version
```

### 4.9 QR Server (EC2 #2)에도 동일하게 설치

```bash
# Main Server에서 나가기
exit
```

```powershell
# QR Server에 접속
ssh -i keeping-key.pem ec2-user@15.165.139.178
```

**4.5 ~ 4.8 과정을 QR Server에서도 똑같이 반복!**

```bash
# 한 번에 복사해서 실행해도 됨
sudo dnf update -y
sudo dnf install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker $USER
sudo curl -L "https://github.com/docker/compose/releases/latest/download/docker-compose-$(uname -s)-$(uname -m)" -o /usr/local/bin/docker-compose
sudo chmod +x /usr/local/bin/docker-compose
exit
```

다시 접속해서:
```bash
# 스왑 메모리
sudo fallocate -l 2G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile swap swap defaults 0 0' | sudo tee -a /etc/fstab

# Git
sudo dnf install -y git

# 확인
docker --version
docker-compose --version
free -h
```

---

## Step 5: 도메인 & Route 53 설정

> 도메인 = 사람이 기억하기 쉬운 주소 (api.keeping.o-r.kr)
> Route 53 = AWS에서 도메인 → IP 연결해주는 서비스

### 5.1 Route 53 페이지로 이동

```
1. AWS Console 상단 검색창에 "Route 53" 입력
2. "Route 53" 클릭
3. Route 53 대시보드 화면 나옴
```

### 5.2 도메인 구매 (옵션 A: AWS에서 구매)

```
1. 왼쪽 메뉴에서 "Registered domains" 클릭
2. 오른쪽 상단 "Register domains" 버튼 클릭
3. 검색창에 원하는 도메인 입력 (예: keeping-app)
4. 사용 가능한 도메인 목록 나옴
   - .com → 약 $13/년
   - .net → 약 $11/년
   - .io → 약 $39/년
5. 원하는 것 선택 후 "Proceed to checkout" 클릭
6. 기간 선택: 1 year
7. "Auto-renew" 체크 해제 (원하면)
8. 연락처 정보 입력 (영문):
   - First Name: 영문 이름
   - Last Name: 영문 성
   - Email: 본인 이메일 (중요!)
   - Phone: +82.10XXXXXXXX (국가번호+번호)
   - Address: 영문 주소
9. "Submit" 클릭
10. 결제 완료
11. 이메일 확인 필요 → 이메일에서 확인 링크 클릭!
12. 도메인 활성화까지 10분~24시간 소요
```

### 5.2-B 도메인 구매 (옵션 B: 가비아에서 구매 - 더 저렴)

```
1. https://www.gabia.com/ 접속
2. 원하는 도메인 검색
3. 구매 (~1만원/년)
4. 결제 완료 후 "My 가비아" > "도메인" > "DNS 관리" 로 이동
```

### 5.3 호스팅 영역 생성

> 도메인을 AWS에서 사용하려면 "호스팅 영역"이 필요함

```
1. Route 53 > 왼쪽 메뉴 "Hosted zones" 클릭
2. 오른쪽 상단 "Create hosted zone" 버튼 클릭
3. 오른쪽에 설정 패널 나옴:

   Domain name: keeping.o-r.kr
   (구매한 도메인 입력, www 없이!)

   Description: (비워도 됨)

   Type: Public hosted zone
   (기본값 그대로)

4. 맨 아래 "Create hosted zone" 버튼 클릭
5. 호스팅 영역 상세 페이지 나옴
```

### 5.4 네임서버 확인 (가비아에서 구매한 경우)

> AWS에서 구매했으면 이 단계 생략!

```
1. 방금 만든 호스팅 영역 클릭
2. "NS" 타입 레코드 확인
3. Value에 4개의 네임서버 주소가 있음:
   ns-123.awsdns-45.com
   ns-678.awsdns-90.net
   ns-234.awsdns-56.org
   ns-345.awsdns-78.co.uk

4. 이 4개를 복사해두기

5. 가비아로 돌아가기:
   - My 가비아 > 도메인 > 해당 도메인 > 네임서버 설정
   - 기존 네임서버 삭제
   - AWS 네임서버 4개 입력
   - 저장

6. 네임서버 변경 반영까지 최대 48시간 소요
   (보통 10분~2시간)
```

### 5.5 A 레코드 생성 - api 서브도메인 (EC2 #1용)

```
1. Route 53 > Hosted zones > 방금 만든 호스팅 영역 클릭
2. 오른쪽 상단 "Create record" 버튼 클릭
3. 설정:

   Record name: api
   (입력하면 자동으로 api.keeping.o-r.kr 됨)

   Record type: A - Routes traffic to an IPv4 address...
   (기본값 그대로)

   Value: 54.116.93.155
   (EC2 #1의 Elastic IP 입력!!)

   TTL (seconds): 300
   (기본값 그대로)

4. "Create records" 버튼 클릭
```

### 5.6 A 레코드 생성 - qr 서브도메인 (EC2 #2용)

```
1. 다시 "Create record" 버튼 클릭
2. 설정:

   Record name: qr
   (입력하면 자동으로 qr.keeping.o-r.kr 됨)

   Record type: A
   (기본값 그대로)

   Value: 15.165.139.178
   (EC2 #2의 Elastic IP 입력!!)

   TTL (seconds): 300

3. "Create records" 버튼 클릭
```

### 5.7 레코드 확인

```
이제 호스팅 영역에 아래 레코드들이 있어야 함:

| Record name          | Type | Value           |
|---------------------|------|-----------------|
| keeping.o-r.kr      | NS   | (네임서버 4개)   |
| keeping.o-r.kr      | SOA  | (자동 생성)      |
| api.keeping.o-r.kr     | A    | 54.116.93.155    |
| qr.keeping.o-r.kr      | A    | 15.165.139.178    |
```

### 5.8 DNS 전파 확인

> 설정 후 바로 안 될 수 있음. 최대 10분 기다리기

**Windows에서 확인:**
```powershell
# PowerShell 열고

# api 도메인 확인
nslookup api.keeping.o-r.kr
# 결과: Address: 54.116.93.155 (EC2 #1 IP)

# qr 도메인 확인
nslookup qr.keeping.o-r.kr
# 결과: Address: 15.165.139.178 (EC2 #2 IP)
```

**안 되면:**
```
- 10분 기다렸다가 다시 시도
- 가비아 네임서버 변경한 경우 최대 48시간 걸림
- Record의 Value에 올바른 IP 입력했는지 확인
```

---

## Step 6: 프로젝트 배포

> 이 단계에서는 GitHub에서 코드를 가져와서 Docker로 실행

### 6.1 로컬 PC에서 Docker Hub 준비

> Docker Hub = Docker 이미지 저장소 (GitHub처럼)
> 이미지를 여기 올려두면 EC2에서 받아서 실행

**Docker Hub 계정 만들기:**
```
1. https://hub.docker.com/ 접속
2. "Sign Up" 클릭
3. 계정 생성 (무료)
4. 이메일 인증
5. 로그인
```

**로컬 PC에 Docker Desktop 설치 (이미 있으면 생략):**
```
1. https://www.docker.com/products/docker-desktop/ 접속
2. "Download for Windows" 클릭
3. 설치 파일 실행
4. 설치 완료 후 PC 재시작
5. Docker Desktop 실행
```

### 6.2 로컬에서 Docker 이미지 빌드 & Push

**PowerShell 열고:**

```powershell
# 프로젝트 폴더로 이동
cd C:\keeping\backend

# Gradle 빌드 (테스트 생략하고 빌드만)
.\gradlew clean build -x test

# QR Service 빌드
.\gradlew :services:qr-service:build -x test
```

빌드 성공하면 이런 메시지:
```
BUILD SUCCESSFUL in 2m 30s
```

**Docker 이미지 빌드:**

```powershell
# {YOUR_DOCKER_USERNAME}을 본인 Docker Hub 계정으로 변경!
# 예: welikewatermelon

# Monolith 이미지 빌드
docker build -t {YOUR_DOCKER_USERNAME}/keeping-monolith:latest .

# QR Service 이미지 빌드
docker build -t {YOUR_DOCKER_USERNAME}/keeping-qr-service:latest ./services/qr-service
```

**Docker Hub에 로그인 & Push:**

```powershell
# Docker Hub 로그인
docker login
# Username: (Docker Hub 계정명 입력)
# Password: (비밀번호 입력)

# Monolith 이미지 업로드
docker push {YOUR_DOCKER_USERNAME}/keeping-monolith:latest

# QR Service 이미지 업로드
docker push {YOUR_DOCKER_USERNAME}/keeping-qr-service:latest
```

업로드 완료되면:
```
latest: digest: sha256:xxxxx size: 1234
```

### 6.3 EC2 #1 (Main Server)에 접속

```powershell
# 키 파일 있는 곳으로 이동
cd C:\keys  # (또는 키 파일 있는 경로)

# SSH 접속
ssh -i keeping-key.pem ec2-user@54.116.93.155
```

### 6.4 EC2 #1에서 프로젝트 Clone

```bash
# keeping 폴더 만들고 이동
mkdir -p ~/keeping && cd ~/keeping

# GitHub에서 코드 가져오기
git clone https://github.com/{YOUR_GITHUB_USERNAME}/keeping-backend.git

# 폴더로 이동
cd keeping-backend

# 브랜치 변경 (필요한 경우)
git checkout refactor/msa-migration

# 확인
ls -la
```

### 6.5 EC2 #1 환경변수 파일 생성 (.env)

```bash
# deploy/monolith 폴더로 이동
cd ~/keeping/keeping-backend/deploy/monolith

# .env 파일 만들기
nano .env
```

**nano 에디터 사용법:**
```
- 화살표 키로 이동
- 글자 입력하면 바로 입력됨
- Ctrl + O → 저장 (Enter로 확인)
- Ctrl + X → 나가기
```

**.env 파일 내용 (복사해서 붙여넣기):**

```bash
# Docker Hub 계정 (본인 계정으로 수정!)
DOCKER_USERNAME=welikewatermelon

# MySQL 비밀번호 (복잡하게 설정!)
MYSQL_ROOT_PASSWORD=YourPassword123!@#

# JWT 시크릿 (64자 이상, 아무 문자나)
JWT_SECRET=KeepingAppSecretKey2024VeryLongAndSecureStringForJWTToken1234567890

# 내부 서비스 인증 토큰 (QR ↔ Monolith 통신용)
INTERNAL_AUTH_TOKEN=InternalServiceToken2024KeepingApp123456

# 프론트엔드 URL
FE_BASE_URL=https://keeping.o-r.kr

# 토스페이먼츠 (테스트 키)
TOSS_SECRET_KEY=test_sk_Gv6LjeKD8aBnMEWAZA0Y3wYxAdXy

# AWS S3 (없으면 비워도 됨)
AWS_ACCESS_KEY=
AWS_SECRET_KEY=
AWS_REGION=ap-northeast-2
AWS_S3_BUCKET=
```

**저장하고 나가기:**
```
Ctrl + O  (저장)
Enter     (파일명 확인)
Ctrl + X  (나가기)
```

### 6.6 EC2 #1에서 Docker Compose 실행

```bash
# 현재 위치 확인 (deploy/monolith 폴더여야 함)
pwd
# 출력: /home/ec2-user/keeping/keeping-backend/deploy/monolith

# Docker Hub에 로그인
docker login
# Username, Password 입력

# 이미지 다운로드
docker-compose pull
```

다운로드 완료되면:
```
Pulling mysql   ... done
Pulling redis   ... done
Pulling monolith ... done
```

```bash
# 컨테이너 실행
docker-compose up -d

# -d = 백그라운드에서 실행 (터미널 안 막힘)
```

실행 성공하면:
```
Creating keeping-mysql    ... done
Creating keeping-redis    ... done
Creating keeping-monolith ... done
```

```bash
# 로그 확인 (실시간)
docker-compose logs -f

# 로그에서 이런 메시지 나오면 성공:
# Started KeepingApplication in XX seconds

# 로그 보기 멈추려면: Ctrl + C
```

```bash
# 컨테이너 상태 확인
docker-compose ps

# 모든 컨테이너가 "Up" 상태면 OK
# NAME              STATUS
# keeping-mysql     Up
# keeping-redis     Up
# keeping-monolith  Up
```

### 6.7 EC2 #1 헬스체크

```bash
# 서버가 정상 동작하는지 확인
curl http://localhost:8080/actuator/health

# 성공하면:
# {"status":"UP"}
```

### 6.8 EC2 #2 (QR Service Server)에 접속

```bash
# 현재 EC2 #1에서 나가기
exit
```

```powershell
# QR Server에 접속
ssh -i keeping-key.pem ec2-user@15.165.139.178
```

### 6.9 EC2 #2에서 프로젝트 Clone

```bash
# 폴더 만들고 Clone
mkdir -p ~/keeping && cd ~/keeping
git clone https://github.com/{YOUR_GITHUB_USERNAME}/keeping-backend.git
cd keeping-backend
git checkout refactor/msa-migration
```

### 6.10 EC2 #2 환경변수 파일 생성 (.env)

```bash
cd ~/keeping/keeping-backend/deploy/qr-service
nano .env
```

**.env 파일 내용:**

```bash
# Docker Hub 계정
DOCKER_USERNAME=welikewatermelon

# MySQL 비밀번호
MYSQL_ROOT_PASSWORD=YourPassword123!@#

# JWT 시크릿 (EC2 #1과 동일해야 함!)
JWT_SECRET=KeepingAppSecretKey2024VeryLongAndSecureStringForJWTToken1234567890

# 내부 서비스 인증 토큰 (EC2 #1과 동일해야 함!)
INTERNAL_AUTH_TOKEN=InternalServiceToken2024KeepingApp123456

# Monolith 서버 Private IP (중요!)
MONOLITH_HOST=172.31.11.13

# 토스페이먼츠
TOSS_SECRET_KEY=test_sk_Gv6LjeKD8aBnMEWAZA0Y3wYxAdXy

# 로드테스트 백도어 (운영 시 false)
LOADTEST_BACKDOOR_ENABLED=false
```

> **중요!**
> - `JWT_SECRET`, `INTERNAL_AUTH_TOKEN`은 EC2 #1과 **완전히 동일해야** 함!
> - `MONOLITH_HOST`는 EC2 #1의 **Private IP** (172.31.11.13) 입력!

**저장하고 나가기:** Ctrl+O → Enter → Ctrl+X

### 6.11 EC2 #2에서 Docker Compose 실행

```bash
# Docker Hub 로그인
docker login

# 이미지 다운로드
docker-compose pull

# 컨테이너 실행
docker-compose up -d

# 로그 확인
docker-compose logs -f
# Started QrServiceApplication in XX seconds 보이면 성공
# Ctrl + C로 나가기

# 상태 확인
docker-compose ps
```

### 6.12 EC2 #2 헬스체크

```bash
curl http://localhost:8082/actuator/health

# 성공하면:
# {"status":"UP"}
```

### 6.13 서버 간 통신 테스트

```bash
# EC2 #2에서 EC2 #1(Monolith)에 접근 테스트
curl http://172.31.11.13:8080/actuator/health

# 성공하면: {"status":"UP"}
# 실패하면: EC2 #1 보안 그룹에서 8080 포트 허용 확인!
```

---

## Step 7: Nginx + SSL 설정 (양쪽 서버)

> **도메인 분리 구조이므로 양쪽 서버에 Nginx 설치!**
> - EC2 #1: api.keeping.o-r.kr → Monolith
> - EC2 #2: qr.keeping.o-r.kr → QR Service

---

## Part A: EC2 #1 (Monolith) Nginx 설정

### 7.1 EC2 #1에 접속

```powershell
# PowerShell에서
cd C:\keys
ssh -i keeping-key.pem ec2-user@54.116.93.155
```

### 7.2 Nginx 설치

```bash
# Nginx 설치
sudo dnf install -y nginx

# 결과: Complete!
```

```bash
# Nginx 시작 & 자동 시작 설정
sudo systemctl start nginx
sudo systemctl enable nginx
```

```bash
# 상태 확인
sudo systemctl status nginx
# "active (running)" 보이면 OK
# q 누르면 나감
```

### 7.3 브라우저에서 테스트

```
http://54.116.93.155
(EC2 #1의 Elastic IP)

Nginx 기본 페이지 나오면 성공!
```

### 7.4 Certbot 설치 (SSL 도구)

```bash
# Certbot 설치
sudo dnf install -y certbot python3-certbot-nginx
```

### 7.5 SSL 인증서 발급

> **중요!** DNS 전파 완료되었는지 먼저 확인!
> `nslookup api.keeping.o-r.kr` → IP 나오면 OK

```bash
# SSL 인증서 발급 (본인 도메인으로 변경!)
sudo certbot --nginx -d api.keeping.o-r.kr
```

**진행 과정:**
```
1. 이메일 입력:
   Enter email address: your-email@example.com
   → 본인 이메일 입력 후 Enter

2. 약관 동의:
   (A)gree/(C)ancel: A
   → A 입력 후 Enter

3. 뉴스레터:
   (Y)es/(N)o: N
   → N 입력 후 Enter

4. 성공하면:
   Congratulations! Your certificate and chain have been saved...
```

**실패 시:**
```
- "Could not connect" → DNS 전파 안 됨, 10분 기다리기
- "Connection refused" → 80포트 보안그룹 확인
```

### 7.6 Nginx 설정 파일 생성

```bash
sudo nano /etc/nginx/conf.d/monolith.conf
```

**아래 내용 복사해서 붙여넣기 (api.keeping.o-r.kr을 본인 도메인으로!):**

```nginx
# ========================================
# EC2 #1: Monolith Nginx Configuration
# ========================================

upstream monolith {
    server 127.0.0.1:8080;
}

# HTTP → HTTPS 리다이렉트
server {
    listen 80;
    server_name api.keeping.o-r.kr;
    return 301 https://$server_name$request_uri;
}

# HTTPS 서버
server {
    listen 443 ssl;
    server_name api.keeping.o-r.kr;

    # SSL 인증서 (Certbot 자동 생성)
    ssl_certificate /etc/letsencrypt/live/api.keeping.o-r.kr/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.keeping.o-r.kr/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    # 로그
    access_log /var/log/nginx/monolith_access.log;
    error_log /var/log/nginx/monolith_error.log;

    # 타임아웃
    proxy_connect_timeout 60s;
    proxy_send_timeout 60s;
    proxy_read_timeout 60s;

    # /internal/* 외부 차단
    location /internal {
        return 403;
    }

    # 모든 /api/* 요청 → Monolith
    location /api {
        proxy_pass http://monolith;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 헬스체크
    location /health {
        return 200 'OK';
        add_header Content-Type text/plain;
    }
}
```

**저장:** Ctrl+O → Enter → Ctrl+X

### 7.7 Nginx 설정 확인 & 재시작

```bash
# 문법 검사
sudo nginx -t

# 성공하면:
# nginx: configuration file ... syntax is ok
# nginx: configuration file ... test is successful
```

```bash
# Nginx 재시작
sudo systemctl restart nginx

# 상태 확인
sudo systemctl status nginx
```

### 7.8 EC2 #1 테스트

```bash
# 서버 내부에서 테스트
curl https://api.keeping.o-r.kr/health
# 결과: OK

curl https://api.keeping.o-r.kr/api/actuator/health
# 결과: {"status":"UP"}
```

**브라우저에서도 테스트:**
```
https://api.keeping.o-r.kr/health → OK
https://api.keeping.o-r.kr/api/actuator/health → {"status":"UP"}
```

### 7.9 SSL 자동 갱신 설정

```bash
# 자동 갱신 테스트
sudo certbot renew --dry-run

# 성공하면:
# Congratulations, all simulated renewals succeeded
```

```bash
# 크론잡 확인 (이미 있을 수 있음)
sudo systemctl list-timers | grep certbot
```

EC2 #1 Nginx 설정 완료!

---

## Part B: EC2 #2 (QR Service) Nginx 설정

### 7.10 EC2 #2에 접속

```bash
# EC2 #1에서 나가기
exit
```

```powershell
# PowerShell에서 EC2 #2 접속
ssh -i keeping-key.pem ec2-user@15.165.139.178
```

### 7.11 Nginx 설치

```bash
# Nginx 설치
sudo dnf install -y nginx

# 시작 & 자동 시작
sudo systemctl start nginx
sudo systemctl enable nginx

# 상태 확인
sudo systemctl status nginx
```

### 7.12 브라우저에서 테스트

```
http://15.165.139.178
(EC2 #2의 Elastic IP)

Nginx 기본 페이지 나오면 성공!
```

### 7.13 Certbot 설치

```bash
sudo dnf install -y certbot python3-certbot-nginx
```

### 7.14 SSL 인증서 발급

> DNS 전파 확인: `nslookup qr.keeping.o-r.kr` → IP 나오면 OK

```bash
# SSL 인증서 발급 (본인 도메인으로!)
sudo certbot --nginx -d qr.keeping.o-r.kr
```

(이메일 입력, 약관 동의 등 동일하게 진행)

### 7.15 Nginx 설정 파일 생성

```bash
sudo nano /etc/nginx/conf.d/qr-service.conf
```

**아래 내용 복사 (qr.keeping.o-r.kr을 본인 도메인으로!):**

```nginx
# ========================================
# EC2 #2: QR Service Nginx Configuration
# ========================================

upstream qr-service {
    server 127.0.0.1:8082;
}

# HTTP → HTTPS 리다이렉트
server {
    listen 80;
    server_name qr.keeping.o-r.kr;
    return 301 https://$server_name$request_uri;
}

# HTTPS 서버
server {
    listen 443 ssl;
    server_name qr.keeping.o-r.kr;

    # SSL 인증서 (Certbot 자동 생성)
    ssl_certificate /etc/letsencrypt/live/qr.keeping.o-r.kr/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/qr.keeping.o-r.kr/privkey.pem;
    include /etc/letsencrypt/options-ssl-nginx.conf;
    ssl_dhparam /etc/letsencrypt/ssl-dhparams.pem;

    # 로그
    access_log /var/log/nginx/qr_access.log;
    error_log /var/log/nginx/qr_error.log;

    # 타임아웃
    proxy_connect_timeout 60s;
    proxy_send_timeout 60s;
    proxy_read_timeout 60s;

    # /internal/* 외부 차단
    location /internal {
        return 403;
    }

    # 모든 /api/* 요청 → QR Service
    location /api {
        proxy_pass http://qr-service;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # 헬스체크
    location /health {
        return 200 'OK';
        add_header Content-Type text/plain;
    }
}
```

**저장:** Ctrl+O → Enter → Ctrl+X

### 7.16 Nginx 설정 확인 & 재시작

```bash
# 문법 검사
sudo nginx -t

# 재시작
sudo systemctl restart nginx
```

### 7.17 EC2 #2 테스트

```bash
curl https://qr.keeping.o-r.kr/health
# 결과: OK

curl https://qr.keeping.o-r.kr/api/actuator/health
# 결과: {"status":"UP"}
```

### 7.18 SSL 자동 갱신 설정

```bash
sudo certbot renew --dry-run
```

---

## Part C: 서버 간 통신 테스트

### 7.19 EC2 #2 → EC2 #1 통신 확인

> QR Service가 Monolith에 접근할 수 있는지 테스트

```bash
# EC2 #2에서 실행
curl http://172.31.11.13:8080/actuator/health

# 성공: {"status":"UP"}
# 실패: EC2 #1 보안그룹에서 8080 포트 확인!
```

### 7.20 EC2 #1 → EC2 #2 통신 확인 (선택)

```bash
# EC2 #1에서 실행
curl http://172.31.11.167:8082/actuator/health

# 성공: {"status":"UP"}
```

---

## Part D: 최종 확인

### 7.21 전체 테스트 체크리스트

```
[ ] https://api.keeping.o-r.kr/health → OK
[ ] https://api.keeping.o-r.kr/api/actuator/health → {"status":"UP"}
[ ] https://qr.keeping.o-r.kr/health → OK
[ ] https://qr.keeping.o-r.kr/api/actuator/health → {"status":"UP"}
[ ] EC2 #2에서: curl http://172.31.11.13:8080/actuator/health → {"status":"UP"}
```

### 7.22 복구 트랜잭션 테스트 준비 완료!

```
이제 EC2 #1을 중지해도 qr.keeping.o-r.kr은 접근 가능!

테스트 방법:
1. AWS Console > EC2 > keeping-main > Instance state > Stop
2. https://qr.keeping.o-r.kr/api/qr/... 요청 (여전히 동작!)
3. QR Service 로그에서 "Monolith 연결 실패, 복구 저장" 확인
4. keeping-main 다시 Start
5. 복구 트랜잭션 재시도 확인
```

Step 7 완료!

---

## Step 8: GitHub Actions CI/CD

> CI/CD = 코드 Push하면 자동으로 빌드 → 자동으로 서버에 배포
> 수동으로 EC2 접속해서 배포할 필요 없어짐!

### 8.1 Docker Hub Access Token 생성

> GitHub Actions에서 Docker Hub에 이미지 Push하려면 토큰 필요

```
1. https://hub.docker.com/ 로그인
2. 우측 상단 프로필 아이콘 클릭
3. "My Account" 클릭
4. 왼쪽 메뉴에서 "Security" 클릭
5. "New Access Token" 버튼 클릭
6. 설정:
   - Access Token Description: keeping-github-actions
   - Access permissions: Read & Write
7. "Generate" 클릭
8. 토큰 복사해서 메모장에 저장! (한 번만 보여줌!)
   예: dckr_pat_XXXXXXXXXXXX
```

### 8.2 GitHub Secrets 설정

> Secrets = 비밀번호/API키 등 민감한 정보를 안전하게 저장

```
1. GitHub 저장소 페이지로 이동
   https://github.com/{YOUR_USERNAME}/keeping-backend

2. 상단 탭에서 "Settings" 클릭

3. 왼쪽 메뉴에서 스크롤 내려서
   "Secrets and variables" 클릭 → "Actions" 클릭

4. 오른쪽 "New repository secret" 버튼 클릭
```

**아래 6개 Secret 추가:**

```
Secret 1:
  Name: DOCKER_USERNAME
  Secret: welikewatermelon  (Docker Hub 계정명)
  → "Add secret" 클릭

Secret 2:
  Name: DOCKER_PASSWORD
  Secret: dckr_pat_XXXXXXXXXXXX  (Docker Hub 토큰)
  → "Add secret" 클릭

Secret 3:
  Name: EC2_MAIN_HOST
  Secret: 54.116.93.155  (EC2 #1 Elastic IP)
  → "Add secret" 클릭

Secret 4:
  Name: EC2_QR_HOST
  Secret: 15.165.139.178  (EC2 #2 Elastic IP)
  → "Add secret" 클릭

Secret 5:
  Name: EC2_USER
  Secret: ec2-user
  → "Add secret" 클릭

Secret 6:
  Name: EC2_SSH_KEY
  Secret: (keeping-key.pem 파일 내용 전체)
  → "Add secret" 클릭
```

**keeping-key.pem 내용 복사 방법:**
```powershell
# PowerShell에서
notepad C:\keys\keeping-key.pem

# 또는
cat C:\keys\keeping-key.pem

# 전체 내용 복사 (-----BEGIN 부터 -----END 까지 전부!)
```

예시 (형식만 참고):
```
-----BEGIN RSA PRIVATE KEY-----
MIIEpAIBAAKCAQEA1234567890abcdefghijklmnop...
여러 줄의 암호화된 텍스트...
-----END RSA PRIVATE KEY-----
```

### 8.3 GitHub Actions Workflow 파일 생성

**로컬 PC에서:**

```powershell
# 프로젝트 폴더로 이동
cd C:\keeping\backend

# .github/workflows 폴더 생성
mkdir -p .github/workflows
```

**파일 생성:**
```powershell
# VS Code로 열기
code .github/workflows/deploy.yml
```

**또는 메모장으로:**
```powershell
notepad .github/workflows/deploy.yml
```

### 8.4 Workflow 파일 내용

**아래 내용 전체 복사해서 붙여넣기:**

```yaml
name: Deploy to AWS EC2

# 언제 실행할지 설정
on:
  push:
    branches: [main]  # main 브랜치에 Push할 때
  workflow_dispatch:   # 수동 실행 버튼도 활성화

jobs:
  # ========================================
  # Job 1: 빌드 & Docker Hub Push
  # ========================================
  build:
    runs-on: ubuntu-latest
    steps:
      # 코드 가져오기
      - name: Checkout code
        uses: actions/checkout@v4

      # Java 21 설치
      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: gradle

      # Gradle 빌드
      - name: Build with Gradle
        run: |
          chmod +x gradlew
          ./gradlew clean build -x test
          ./gradlew :services:qr-service:build -x test

      # Docker Hub 로그인
      - name: Login to Docker Hub
        uses: docker/login-action@v3
        with:
          username: ${{ secrets.DOCKER_USERNAME }}
          password: ${{ secrets.DOCKER_PASSWORD }}

      # Monolith Docker 이미지 빌드 & Push
      - name: Build & Push Monolith
        run: |
          docker build -t ${{ secrets.DOCKER_USERNAME }}/keeping-monolith:latest .
          docker push ${{ secrets.DOCKER_USERNAME }}/keeping-monolith:latest

      # QR Service Docker 이미지 빌드 & Push
      - name: Build & Push QR Service
        run: |
          docker build -t ${{ secrets.DOCKER_USERNAME }}/keeping-qr-service:latest ./services/qr-service
          docker push ${{ secrets.DOCKER_USERNAME }}/keeping-qr-service:latest

  # ========================================
  # Job 2: EC2 #1 (Monolith) 배포
  # ========================================
  deploy-main:
    needs: build  # build 완료 후 실행
    runs-on: ubuntu-latest
    steps:
      - name: Deploy Monolith to EC2
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.EC2_SSH_KEY }}" > ~/.ssh/key.pem
          chmod 600 ~/.ssh/key.pem
          ssh -o StrictHostKeyChecking=no -i ~/.ssh/key.pem \
            ${{ secrets.EC2_USER }}@${{ secrets.EC2_MAIN_HOST }} << 'EOF'
            cd ~/keeping/keeping-backend/deploy/monolith
            docker-compose pull
            docker-compose up -d
            docker image prune -f
            echo "Monolith deployed successfully!"
          EOF

  # ========================================
  # Job 3: EC2 #2 (QR Service) 배포
  # ========================================
  deploy-qr:
    needs: [build, deploy-main]  # build, deploy-main 완료 후 실행
    runs-on: ubuntu-latest
    steps:
      - name: Deploy QR Service to EC2
        run: |
          mkdir -p ~/.ssh
          echo "${{ secrets.EC2_SSH_KEY }}" > ~/.ssh/key.pem
          chmod 600 ~/.ssh/key.pem
          ssh -o StrictHostKeyChecking=no -i ~/.ssh/key.pem \
            ${{ secrets.EC2_USER }}@${{ secrets.EC2_QR_HOST }} << 'EOF'
            cd ~/keeping/keeping-backend/deploy/qr-service
            docker-compose pull
            docker-compose up -d
            docker image prune -f
            echo "QR Service deployed successfully!"
          EOF
```

**저장하고 닫기**

### 8.5 Workflow 파일 Push

```powershell
# Git에 추가
git add .github/workflows/deploy.yml

# 커밋
git commit -m "Add GitHub Actions CI/CD workflow"

# Push
git push origin main
```

### 8.6 GitHub Actions 실행 확인

```
1. GitHub 저장소 페이지로 이동
2. 상단 탭에서 "Actions" 클릭
3. "Deploy to AWS EC2" Workflow 보임
4. 노란색 동그라미 = 실행 중
   초록색 체크 = 성공
   빨간색 X = 실패

5. 클릭하면 상세 로그 확인 가능
```

**진행 순서:**
```
build (빌드 & Docker Push)
  ↓
deploy-main (EC2 #1 배포)
  ↓
deploy-qr (EC2 #2 배포)
```

### 8.7 수동 실행 방법

```
1. GitHub > Actions 탭
2. 왼쪽에서 "Deploy to AWS EC2" 클릭
3. 오른쪽 "Run workflow" 버튼 클릭
4. Branch 선택 후 "Run workflow" 클릭
5. 새로고침하면 실행 시작됨
```

### 8.8 실패 시 디버깅

**빌드 실패:**
```
- Actions > 실패한 workflow 클릭
- "build" job 클릭
- 빨간색 X 표시된 step 클릭
- 에러 메시지 확인
```

**배포 실패 (SSH 에러):**
```
1. EC2_SSH_KEY가 올바른지 확인
   - -----BEGIN 부터 -----END----- 까지 전체 복사했는지
   - 앞뒤 공백/줄바꿈 없는지

2. EC2 보안 그룹에서 22 포트 열려있는지
   - 0.0.0.0/0 또는 GitHub Actions IP 범위

3. EC2 인스턴스 실행 중인지
```

**Permission denied:**
```
- keeping-key.pem 파일 내용 다시 복사
- 줄바꿈 문자가 깨지지 않았는지 확인
```

### 8.9 배포 완료 확인

```bash
# EC2 #1에서
ssh -i keeping-key.pem ec2-user@54.116.93.155

docker-compose ps
docker-compose logs --tail 50

# 최근 배포 시간 확인
docker inspect keeping-monolith | grep Created
```

### 8.10 앞으로 배포 방법

```
코드 수정 → git push origin main → 자동 배포!

또는

GitHub > Actions > Run workflow 클릭
```

**이제 수동 배포 필요 없음!**

---

# Part 4: 운영 & 관리

## 자주 쓰는 명령어

```bash
# Docker 상태
docker-compose ps
docker-compose logs -f

# 재시작
docker-compose restart

# 업데이트 배포
docker-compose pull
docker-compose up -d

# Nginx 로그
sudo tail -f /var/log/nginx/keeping_access.log
sudo tail -f /var/log/nginx/keeping_error.log

# SSL 인증서 확인
sudo certbot certificates
```

## 트러블슈팅

### 502 Bad Gateway
```bash
# 백엔드 서비스 확인
docker-compose ps
docker-compose logs

# Nginx → 백엔드 연결 확인
curl http://localhost:8080/actuator/health
```

### SSL 인증서 만료
```bash
sudo certbot renew
sudo systemctl reload nginx
```

### 서버 간 통신 실패
```bash
# EC2 #1에서 QR Service 접근 테스트
curl http://{QR_SERVER_IP}:8082/actuator/health

# 안 되면 보안 그룹 확인!
```

---

# 체크리스트 요약

## 필수 작업
- [ ] AWS 계정 + MFA
- [ ] EC2 2대 생성 (t3.small)
- [ ] Elastic IP 2개 연결
- [ ] Docker 설치
- [ ] 도메인 구매 & Route 53 설정
- [ ] 프로젝트 배포 (Monolith, QR Service)
- [ ] Nginx + SSL 설정
- [ ] GitHub Actions CI/CD

---

# 예상 비용 (월간)

| 리소스 | 사양 | 비용 |
|--------|------|------|
| EC2 #1 | t3.small | ~$18 |
| EC2 #2 | t3.small | ~$18 |
| EBS | 20GB x 2 | ~$4 |
| Elastic IP | 2개 (사용 중) | $0 |
| Route 53 | 호스팅 영역 | ~$0.50 |
| 도메인 | 연간 | ~$1/월 ($12/년) |
| **합계** | | **~$42/월** |
