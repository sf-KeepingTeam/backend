# AWS EC2 부하 테스트 환경 구축 가이드

## 개요

QR 결제 서비스 부하 테스트를 위한 AWS EC2 3대 환경을 구축합니다.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              AWS VPC                                     │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                          │
│  ┌──────────────────┐    ┌──────────────────┐    ┌──────────────────┐  │
│  │ Server 1 (Nginx) │    │ Server 2 (Mono)  │    │ Server 3 (QR)    │  │
│  │ Public + Private │    │ Public + Private │    │ Public + Private │  │
│  │                  │    │                  │    │                  │  │
│  │  Port 80: 외부   │    │  Port 8080: VPC  │    │  Port 8082: VPC  │  │
│  │  Port 22: 내 IP  │    │  Port 22: 내 IP  │    │  Port 22: 내 IP  │  │
│  └──────────────────┘    └──────────────────┘    └──────────────────┘  │
│                                                                          │
└─────────────────────────────────────────────────────────────────────────┘
         ▲
         │ k6 부하 테스트 (Port 80)
         │
    ┌────┴────┐
    │  Local  │
    └─────────┘
```

**보안 전략**: 퍼블릭 IP는 3대 모두 활성화하되, **보안 그룹(Security Group)**으로 접근을 엄격히 통제

---

## Part 1: 키 페어 생성 (SSH 접속용)

### Step 1-1: EC2 대시보드 접속
1. AWS 콘솔 로그인: https://console.aws.amazon.com/
2. 상단 검색창에 `EC2` 입력
3. **EC2** 클릭

### Step 1-2: 키 페어 생성
1. 왼쪽 사이드바에서 **네트워크 및 보안** 섹션 찾기
2. **키 페어** 클릭
3. 우측 상단 **키 페어 생성** 버튼 클릭
4. 다음과 같이 입력:

| 항목 | 값 |
|------|-----|
| 이름 | `keeping-loadtest` |
| 키 페어 유형 | RSA |
| 프라이빗 키 파일 형식 | `.pem` (Mac/Linux/Windows 10+) 또는 `.ppk` (PuTTY 사용 시) |

5. **키 페어 생성** 버튼 클릭
6. `keeping-loadtest.pem` 파일이 자동 다운로드됨

> ⚠️ **중요**: 이 파일은 다시 다운로드할 수 없습니다. 안전한 곳에 보관하세요!

### Step 1-3: 키 파일 권한 설정 (Mac/Linux만 해당)
```bash
chmod 400 ~/Downloads/keeping-loadtest.pem
```

---

## Part 2: 보안 그룹 생성

### Step 2-1: 보안 그룹 메뉴 접속
1. EC2 대시보드 왼쪽 사이드바
2. **네트워크 및 보안** → **보안 그룹** 클릭
3. 우측 상단 **보안 그룹 생성** 버튼 클릭

---

### Step 2-2: SG-Nginx 보안 그룹 생성

**기본 세부 정보**

| 항목 | 값 |
|------|-----|
| 보안 그룹 이름 | `SG-Nginx` |
| 설명 | `Nginx Gateway - HTTP public, SSH my IP only` |
| VPC | 기본 VPC 선택 (변경하지 않음) |

**인바운드 규칙** - `규칙 추가` 버튼을 눌러 2개 추가:

| 유형 | 포트 범위 | 소스 | 설명 |
|------|----------|------|------|
| HTTP | 80 | `Anywhere-IPv4` (0.0.0.0/0) | 외부 HTTP 트래픽 허용 |
| SSH | 22 | `내 IP` (자동으로 채워짐) | 내 PC에서만 SSH 허용 |

**아웃바운드 규칙** - 기본값 유지 (모든 트래픽 허용)

➡️ **보안 그룹 생성** 버튼 클릭

---

### Step 2-3: SG-Monolith 보안 그룹 생성

다시 **보안 그룹 생성** 버튼 클릭

**기본 세부 정보**

| 항목 | 값 |
|------|-----|
| 보안 그룹 이름 | `SG-Monolith` |
| 설명 | `Monolith Server - 8080 VPC only, SSH my IP only` |
| VPC | 기본 VPC 선택 |

**인바운드 규칙** - `규칙 추가` 버튼을 눌러 2개 추가:

| 유형 | 포트 범위 | 소스 | 설명 |
|------|----------|------|------|
| 사용자 지정 TCP | 8080 | `사용자 지정` → `10.0.0.0/16` 입력 | VPC 내부에서만 접근 |
| SSH | 22 | `내 IP` | 내 PC에서만 SSH 허용 |

> 💡 **팁**: VPC CIDR이 `10.0.0.0/16`이 아닐 수 있습니다.
> VPC 메뉴에서 기본 VPC의 IPv4 CIDR을 확인하세요. (보통 `172.31.0.0/16`)

➡️ **보안 그룹 생성** 버튼 클릭

---

### Step 2-4: SG-QR 보안 그룹 생성

다시 **보안 그룹 생성** 버튼 클릭

**기본 세부 정보**

| 항목 | 값 |
|------|-----|
| 보안 그룹 이름 | `SG-QR` |
| 설명 | `QR Service - 8082 VPC only, SSH my IP only` |
| VPC | 기본 VPC 선택 |

**인바운드 규칙** - `규칙 추가` 버튼을 눌러 2개 추가:

| 유형 | 포트 범위 | 소스 | 설명 |
|------|----------|------|------|
| 사용자 지정 TCP | 8082 | `사용자 지정` → `10.0.0.0/16` 입력 | VPC 내부에서만 접근 |
| SSH | 22 | `내 IP` | 내 PC에서만 SSH 허용 |

➡️ **보안 그룹 생성** 버튼 클릭

---

### Step 2-5: VPC CIDR 확인 방법 (필요시)

1. AWS 콘솔 상단 검색창에 `VPC` 입력
2. **VPC** 클릭
3. 왼쪽 메뉴 **내 VPC** 클릭
4. **기본값**이 `예`인 VPC의 **IPv4 CIDR** 열 확인
5. 보통 `172.31.0.0/16` 또는 `10.0.0.0/16`

> 확인한 CIDR 값을 SG-Monolith, SG-QR의 8080, 8082 포트 소스에 입력하세요.

---

## Part 3: EC2 인스턴스 생성

### Step 3-1: 인스턴스 시작 메뉴 접속
1. EC2 대시보드로 돌아가기
2. 왼쪽 사이드바 **인스턴스** 클릭
3. 우측 상단 **인스턴스 시작** 버튼 클릭

---

### Step 3-2: Server 1 - Nginx 서버 생성

**이름 및 태그**

| 항목 | 값 |
|------|-----|
| 이름 | `loadtest-nginx` |

**애플리케이션 및 OS 이미지**

| 항목 | 값 |
|------|-----|
| Quick Start | Amazon Linux 선택 |
| AMI | Amazon Linux 2023 AMI (프리 티어 사용 가능) |

**인스턴스 유형**

| 항목 | 값 |
|------|-----|
| 인스턴스 유형 | `t3.small` (또는 테스트용 `t3.micro`) |

**키 페어**

| 항목 | 값 |
|------|-----|
| 키 페어 이름 | `keeping-loadtest` (Part 1에서 생성한 것) |

**네트워크 설정** - `편집` 버튼 클릭

| 항목 | 값 |
|------|-----|
| VPC | 기본 VPC |
| 서브넷 | 기본 설정 유지 |
| 퍼블릭 IP 자동 할당 | **활성화** ⭐ |
| 방화벽(보안 그룹) | **기존 보안 그룹 선택** |
| 보안 그룹 | `SG-Nginx` 체크 ✅ |

**스토리지 구성**

| 항목 | 값 |
|------|-----|
| 크기 | `20` GiB |
| 볼륨 유형 | gp3 |

➡️ **인스턴스 시작** 버튼 클릭

---

### Step 3-3: Server 2 - Monolith 서버 생성

다시 **인스턴스 시작** 버튼 클릭

**이름 및 태그**

| 항목 | 값 |
|------|-----|
| 이름 | `loadtest-monolith` |

**애플리케이션 및 OS 이미지**

| 항목 | 값 |
|------|-----|
| AMI | Amazon Linux 2023 AMI |

**인스턴스 유형**

| 항목 | 값 |
|------|-----|
| 인스턴스 유형 | `t3.small` |

**키 페어**

| 항목 | 값 |
|------|-----|
| 키 페어 이름 | `keeping-loadtest` |

**네트워크 설정** - `편집` 버튼 클릭

| 항목 | 값 |
|------|-----|
| VPC | 기본 VPC |
| 서브넷 | 기본 설정 유지 |
| 퍼블릭 IP 자동 할당 | **활성화** ⭐ |
| 방화벽(보안 그룹) | **기존 보안 그룹 선택** |
| 보안 그룹 | `SG-Monolith` 체크 ✅ |

**스토리지 구성**

| 항목 | 값 |
|------|-----|
| 크기 | `20` GiB |

➡️ **인스턴스 시작** 버튼 클릭

---

### Step 3-4: Server 3 - QR 서버 생성

다시 **인스턴스 시작** 버튼 클릭

**이름 및 태그**

| 항목 | 값 |
|------|-----|
| 이름 | `loadtest-qr` |

**애플리케이션 및 OS 이미지**

| 항목 | 값 |
|------|-----|
| AMI | Amazon Linux 2023 AMI |

**인스턴스 유형**

| 항목 | 값 |
|------|-----|
| 인스턴스 유형 | `t3.small` |

**키 페어**

| 항목 | 값 |
|------|-----|
| 키 페어 이름 | `keeping-loadtest` |

**네트워크 설정** - `편집` 버튼 클릭

| 항목 | 값 |
|------|-----|
| VPC | 기본 VPC |
| 서브넷 | 기본 설정 유지 |
| 퍼블릭 IP 자동 할당 | **활성화** ⭐ |
| 방화벽(보안 그룹) | **기존 보안 그룹 선택** |
| 보안 그룹 | `SG-QR` 체크 ✅ |

**스토리지 구성**

| 항목 | 값 |
|------|-----|
| 크기 | `20` GiB |

➡️ **인스턴스 시작** 버튼 클릭

---

## Part 4: 인스턴스 정보 확인 및 정리

### Step 4-1: 인스턴스 목록 확인
1. EC2 대시보드 → **인스턴스** 클릭
2. 3개 인스턴스가 `실행 중` 상태가 될 때까지 대기 (1-2분)

### Step 4-2: IP 주소 기록

각 인스턴스를 클릭하여 아래 정보를 메모하세요:

| 서버 | 이름 | 퍼블릭 IP | 프라이빗 IP | 보안 그룹 |
|------|------|----------|------------|----------|
| Server 1 | loadtest-nginx | 3.x.x.x | 172.31.x.x | SG-Nginx |
| Server 2 | loadtest-monolith | 3.x.x.x | 172.31.x.x | SG-Monolith |
| Server 3 | loadtest-qr | 3.x.x.x | 172.31.x.x | SG-QR |

> ⚠️ 이 IP 주소들은 나중에 설정 파일에서 사용합니다!

---

## Part 5: SSH 접속 테스트

### Step 5-1: Mac/Linux 터미널에서 접속

```bash
# Nginx 서버 접속
ssh -i ~/Downloads/keeping-loadtest.pem ec2-user@[NGINX_PUBLIC_IP]

# Monolith 서버 접속
ssh -i ~/Downloads/keeping-loadtest.pem ec2-user@[MONOLITH_PUBLIC_IP]

# QR 서버 접속
ssh -i ~/Downloads/keeping-loadtest.pem ec2-user@[QR_PUBLIC_IP]
```

예시:
```bash
ssh -i ~/Downloads/keeping-loadtest.pem ec2-user@3.35.123.45
```

### Step 5-2: Windows에서 접속

**방법 A: Windows 10/11 기본 터미널 (PowerShell 또는 CMD)**
```powershell
ssh -i C:\Users\[사용자명]\Downloads\keeping-loadtest.pem ec2-user@[PUBLIC_IP]
```

**방법 B: PuTTY 사용**
1. PuTTYgen으로 `.pem` → `.ppk` 변환
2. PuTTY 실행
3. Host Name: `ec2-user@[PUBLIC_IP]`
4. Connection → SSH → Auth → Credentials → Private key file: `.ppk` 파일 선택
5. Open 클릭

### Step 5-3: 접속 성공 확인

접속 후 아래와 같은 화면이 보이면 성공:
```
   ,     #_
   ~\_  ####_        Amazon Linux 2023
  ~~  \_#####\
  ~~     \###|
  ~~       \#/ ___   https://aws.amazon.com/linux/amazon-linux-2023
   ~~       V~' '->
    ~~~         /
      ~~._.   _/
         _/ _/
       _/m/'
[ec2-user@ip-172-31-xx-xx ~]$
```

---

## Part 6: 각 서버에 Docker 설치

**3대 서버 모두** SSH 접속 후 아래 명령어 실행:

```bash
# 패키지 업데이트
sudo yum update -y

# Docker 설치
sudo yum install -y docker

# Docker 서비스 시작 및 자동 시작 설정
sudo systemctl start docker
sudo systemctl enable docker

# ec2-user를 docker 그룹에 추가 (sudo 없이 docker 명령 사용)
sudo usermod -aG docker ec2-user

# 변경 적용을 위해 재접속
exit
```

재접속 후 Docker 확인:
```bash
docker --version
# Docker version 25.x.x 같은 출력이 나오면 성공
```

---

## 완료 체크리스트

- [ ] 키 페어 `keeping-loadtest.pem` 다운로드 완료
- [ ] 보안 그룹 3개 생성 완료 (SG-Nginx, SG-Monolith, SG-QR)
- [ ] EC2 인스턴스 3대 생성 및 실행 중
- [ ] 3대 모두 퍼블릭 IP 할당됨
- [ ] 3대 모두 SSH 접속 성공
- [ ] 3대 모두 Docker 설치 완료

---

## 다음 단계

AWS 환경 준비가 완료되면, 다음 단계로:
1. Docker 이미지를 각 서버로 전송
2. docker-compose로 서비스 시작
3. k6 부하 테스트 실행

이 내용은 별도 가이드에서 안내합니다.
