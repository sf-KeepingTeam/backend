# Keeping Backend

선결제(포인트) 기반 소상공인 결제 플랫폼 백엔드.

고객은 매장에 포인트를 미리 선결제하고, 개인/모임 지갑으로 관리한 뒤 QR로 결제합니다.

## 구성

두 개의 Spring Boot 서비스 + Nginx API Gateway.

- **monolith** (`:8080`, `monolith/`): 인증, 회원, 매장/메뉴, 선결제, 지갑, 알림, 통계
- **qr-service** (`:8082`, `qr-service/`): QR 토큰 발급, CPQR 결제 의도 생성/승인
- **nginx** (`:80`, `gateway/nginx.conf`): 경로 기반 라우팅
- **MySQL 8** + **Redis 7**

monolith와 qr-service는 각각 독립 Gradle 프로젝트이며, 각자의 디렉토리에서 빌드/실행한다.

## 로컬 실행

사전 요구: MySQL `localhost:3306` (user=root / pw=ssafy / db=keeping), Redis `localhost:6379`

```bash
(cd monolith && ./gradlew bootRun)
(cd qr-service && ./gradlew bootRun)
```

## Docker Compose (MSA 전체)

```bash
cp .env.example .env            # JWT_SECRET, TOSS_SECRET_KEY 등 채우기
(cd monolith && ./gradlew clean bootJar -x test)
(cd qr-service && ./gradlew clean bootJar -x test)
docker compose -f docker-compose.msa.yml up -d
```

Nginx: `http://localhost:80` · monolith: `:8080` · qr-service: `:8082` (내부) · MySQL: `:3307` · Redis: `:6379`

## 문서

- **프로젝트 전반 가이드**: [CLAUDE.md](./CLAUDE.md)
- **도메인별 상세**: 각 `monolith/src/main/java/com/ssafy/keeping/domain/<name>/CLAUDE.md`
- **공통 인프라(global)**: [monolith/src/main/java/com/ssafy/keeping/global/CLAUDE.md](./monolith/src/main/java/com/ssafy/keeping/global/CLAUDE.md)
- **qr-service**: [qr-service/CLAUDE.md](./qr-service/CLAUDE.md)
- **데이터 스키마**: [data_erd.md](./data_erd.md)
- **아키텍처·운영 문서**: [docs/](./docs/) (완료된 마이그레이션 기록은 `docs/archive/`)
- **운영 노트**: [0318Troubleshooting.md](./0318Troubleshooting.md), [0319refactoring.md](./0319refactoring.md)
