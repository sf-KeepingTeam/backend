# Nginx 학습 가이드

## 목차
1. [Nginx란?](#1-nginx란)
2. [아키텍처 비교](#2-아키텍처-비교)
3. [단일 Nginx로 여러 서비스 라우팅](#3-단일-nginx로-여러-서비스-라우팅)
4. [실습 예제](#4-실습-예제)
5. [AWS 도메인 구매 + Route 53 설정](#5-aws-도메인-구매--route-53-설정)

---

## 1. Nginx란?

### 정의
- **웹 서버**: 정적 파일(HTML, CSS, JS) 서빙
- **리버스 프록시**: 클라이언트 요청을 내부 서버로 전달
- **로드 밸런서**: 여러 서버에 트래픽 분산
- **SSL 종료**: HTTPS 암호화/복호화 처리

### 왜 Nginx를 쓰나?
```
[사용자] → [Nginx] → [애플리케이션 서버]
```

| 이유 | 설명 |
|------|------|
| 보안 | 실제 앱 포트(8080)를 숨김 |
| SSL/HTTPS | 인증서 관리 쉬움 |
| 로드밸런싱 | 서버 여러 대 운영 시 필수 |
| 정적 파일 | 앱 서버 부하 감소 |
| 캐싱 | 응답 속도 향상 |

---

## 2. 아키텍처 비교

### 방식 A: 도메인별 EC2 분리 (현재 우리 방식)
```
┌─────────────────────────────────────────────────────────┐
│                        DNS                               │
│  api.keeping.o-r.kr → 54.116.93.155 (EC2 #1)            │
│  qr.keeping.o-r.kr  → 15.165.139.178 (EC2 #2)           │
└─────────────────────────────────────────────────────────┘

┌──────────────────┐          ┌──────────────────┐
│     EC2 #1       │          │     EC2 #2       │
│  ┌────────────┐  │          │  ┌────────────┐  │
│  │   Nginx    │  │          │  │   Nginx    │  │
│  │  :443/:80  │  │          │  │  :443/:80  │  │
│  └─────┬──────┘  │          │  └─────┬──────┘  │
│        ↓         │          │        ↓         │
│  ┌────────────┐  │          │  ┌────────────┐  │
│  │  Monolith  │  │          │  │ QR Service │  │
│  │   :8080    │  │          │  │   :8082    │  │
│  └────────────┘  │          │  └────────────┘  │
└──────────────────┘          └──────────────────┘
```

- **장점**: 서비스 독립성, 장애 격리
- **단점**: 서버 비용 증가, 관리 포인트 분산

---

### 방식 B: 단일 Nginx 게이트웨이 (API Gateway 패턴)
```
┌─────────────────────────────────────────────────────────┐
│                        DNS                               │
│  api.keeping.com → 1.2.3.4 (Nginx EC2)                  │
│  (단일 도메인)                                           │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
              ┌──────────────────────┐
              │   EC2 (Nginx Only)   │
              │      1.2.3.4         │
              │   ┌──────────────┐   │
              │   │    Nginx     │   │
              │   │   :443/:80   │   │
              │   └──────┬───────┘   │
              └──────────┼───────────┘
                         │
          ┌──────────────┼──────────────┐
          │              │              │
          ▼              ▼              ▼
    /api/*          /qr/*          /admin/*
          │              │              │
          ▼              ▼              ▼
   ┌──────────┐   ┌──────────┐   ┌──────────┐
   │ EC2 #1   │   │ EC2 #2   │   │ EC2 #3   │
   │ Monolith │   │QR Service│   │  Admin   │
   │  :8080   │   │  :8082   │   │  :8083   │
   └──────────┘   └──────────┘   └──────────┘
```

- **장점**: 중앙 집중 관리, 단일 SSL 인증서, 로드밸런싱 용이
- **단점**: 단일 장애점(SPOF), Nginx 서버 추가 비용

---

## 3. 단일 Nginx로 여러 서비스 라우팅

### 3.1 Path 기반 라우팅
```nginx
# /etc/nginx/conf.d/api.conf

upstream monolith {
    server 172.31.11.13:8080;  # EC2 #1 Private IP
}

upstream qr_service {
    server 172.31.11.167:8082;  # EC2 #2 Private IP
}

server {
    listen 80;
    server_name api.keeping.com;
    return 301 https://$server_name$request_uri;
}

server {
    listen 443 ssl;
    server_name api.keeping.com;

    ssl_certificate /etc/letsencrypt/live/api.keeping.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/api.keeping.com/privkey.pem;

    # /api/* → Monolith
    location /api/ {
        proxy_pass http://monolith;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # /qr/* → QR Service
    location /qr/ {
        proxy_pass http://qr_service;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # /payment/* → QR Service
    location /payment/ {
        proxy_pass http://qr_service;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # 기본 → Monolith
    location / {
        proxy_pass http://monolith;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

### 3.2 서브도메인 기반 라우팅 (단일 Nginx에서)
```nginx
# api.keeping.com → Monolith
server {
    listen 443 ssl;
    server_name api.keeping.com;

    location / {
        proxy_pass http://172.31.11.13:8080;
    }
}

# qr.keeping.com → QR Service
server {
    listen 443 ssl;
    server_name qr.keeping.com;

    location / {
        proxy_pass http://172.31.11.167:8082;
    }
}
```

---

## 4. 실습 예제

### 4.1 시나리오
```
목표: 단일 EC2(Nginx)에서 3개 서비스 라우팅

keeping.com/           → Frontend (React)
keeping.com/api/*      → Backend API
keeping.com/admin/*    → Admin Dashboard
```

### 4.2 인프라 구성

#### Step 1: EC2 3대 생성
```bash
# EC2 #1: Nginx (Gateway) - t2.micro
# EC2 #2: Backend API - t2.small
# EC2 #3: Admin - t2.micro
```

#### Step 2: Nginx EC2에 Docker 설치
```bash
sudo yum update -y
sudo yum install -y docker
sudo systemctl start docker
sudo systemctl enable docker
```

#### Step 3: Nginx 설치
```bash
sudo yum install -y nginx
sudo systemctl start nginx
sudo systemctl enable nginx
```

#### Step 4: Nginx 설정
```bash
sudo vi /etc/nginx/conf.d/gateway.conf
```

```nginx
# Frontend (정적 파일)
server {
    listen 80;
    server_name keeping.com;

    # React 빌드 파일 서빙
    root /var/www/frontend;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    # API 프록시
    location /api/ {
        proxy_pass http://172.31.x.x:8080/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # Admin 프록시
    location /admin/ {
        proxy_pass http://172.31.x.x:8083/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }
}
```

#### Step 5: 설정 테스트 & 적용
```bash
# 문법 검사
sudo nginx -t

# 설정 리로드
sudo systemctl reload nginx
```

---

## 5. AWS 도메인 구매 + Route 53 설정

### 5.1 Route 53에서 도메인 구매

1. AWS Console → Route 53 → "도메인 등록"
2. 원하는 도메인 검색 (예: `keeping.com`)
3. 결제 및 등록 (연 $12~15 정도)

### 5.2 호스팅 영역 생성

```
Route 53 → 호스팅 영역 → 호스팅 영역 생성
도메인 이름: keeping.com
```

### 5.3 DNS 레코드 설정

#### 방식 A: 서비스별 서브도메인
```
| 레코드 이름        | 유형 | 값              | 설명           |
|-------------------|------|-----------------|----------------|
| keeping.com       | A    | 1.2.3.4         | Nginx EC2      |
| api.keeping.com   | A    | 1.2.3.4         | 같은 Nginx     |
| qr.keeping.com    | A    | 1.2.3.4         | 같은 Nginx     |
```

#### 방식 B: 서비스별 EC2
```
| 레코드 이름        | 유형 | 값              | 설명           |
|-------------------|------|-----------------|----------------|
| api.keeping.com   | A    | 54.116.93.155   | EC2 #1         |
| qr.keeping.com    | A    | 15.165.139.178  | EC2 #2         |
```

### 5.4 SSL 인증서 (AWS Certificate Manager)

```bash
# 방법 1: Let's Encrypt (무료, EC2에서 직접)
sudo certbot --nginx -d keeping.com -d api.keeping.com -d qr.keeping.com

# 방법 2: AWS ACM (무료, ALB/CloudFront와 함께 사용)
# AWS Console → Certificate Manager → 인증서 요청
```

---

## 6. 로드밸런싱 예제

### 6.1 여러 백엔드 서버로 분산
```nginx
upstream backend {
    # 라운드 로빈 (기본)
    server 172.31.1.10:8080;
    server 172.31.1.11:8080;
    server 172.31.1.12:8080;
}

# 또는 가중치 설정
upstream backend_weighted {
    server 172.31.1.10:8080 weight=3;  # 3배 더 많이
    server 172.31.1.11:8080 weight=1;
}

# 또는 IP 해시 (세션 유지)
upstream backend_sticky {
    ip_hash;
    server 172.31.1.10:8080;
    server 172.31.1.11:8080;
}

server {
    listen 80;

    location / {
        proxy_pass http://backend;
    }
}
```

---

## 7. 유용한 Nginx 명령어

```bash
# 설정 문법 검사
sudo nginx -t

# 설정 리로드 (무중단)
sudo systemctl reload nginx

# 재시작
sudo systemctl restart nginx

# 상태 확인
sudo systemctl status nginx

# 로그 확인
sudo tail -f /var/log/nginx/access.log
sudo tail -f /var/log/nginx/error.log

# 설정 파일 위치
/etc/nginx/nginx.conf          # 메인 설정
/etc/nginx/conf.d/*.conf       # 추가 설정
```

---

## 8. 정리

| 상황 | 권장 방식 |
|------|----------|
| 서비스 1-2개, 비용 절감 | 각 EC2에 Nginx |
| 서비스 3개+, 중앙 관리 | 단일 Nginx Gateway |
| 고가용성 필요 | AWS ALB + Auto Scaling |
| 글로벌 서비스 | CloudFront + ALB |

### 우리 프로젝트 (현재)
```
도메인 분리 (api. / qr.) + 각 EC2에 Nginx
→ 간단하고 비용 효율적
```

### 대규모 서비스라면
```
단일 도메인 + Nginx Gateway + 로드밸런싱
또는
AWS ALB + Auto Scaling Group
```
