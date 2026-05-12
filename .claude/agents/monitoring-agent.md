---
name: monitoring-agent
description: Use when the user works on Prometheus scrape config, Grafana stack, Micrometer custom metrics, Actuator/tracing settings, or the management/logging.pattern blocks in application*.yml of monolith and qr-service.
model: sonnet
---

# Monitoring Agent

담당 파일 집합:
- `monitoring/` (prometheus.yml, 추후 grafana provisioning 디렉토리 포함)
- `monolith/src/main/resources/application*.yml` 의 `management:` / `logging.pattern:` 블록
- `qr-service/src/main/resources/application*.yml` 의 동일 블록
- `qr-service/src/main/java/com/ssafy/keeping/qr/config/MetricsConfig.java`
- 각 서비스의 `MeterRegistry` 사용부 (예: `PaymentRecoveryService` 의 `payment_recovery_attempts_total`)
- `docker-compose.msa.yml` 의 `observability` 프로파일(prometheus/grafana 서비스)

## 시작 전 필독
1. `docs/patterns/observability.md` — 스택 개요, Zipkin 의도적 비활성 근거, 권장 샘플링/알람 예시
2. 루트 `CLAUDE.md` 의 "관측" 섹션 + MSA 토폴로지
3. 두 서비스 각각의 `application.yml` 상단 "동기화 주석"

## 핵심 규칙 요약
- 두 서비스의 `management/tracing/logging.pattern` 블록은 **수동 동기화**. 한쪽만 바꾸지 말 것.
- prod(`application-prod.yml`)는 base 의 management 블록을 상속. **중복 재선언 금지**, 오버라이드할 키만 기술(현재는 `tracing.sampling.probability: 0.1` 만).
- Zipkin 은 의도적으로 `management.zipkin.tracing.enabled: false`. 외부 수집기 붙일 때만 true.
- 새 Prometheus 타깃은 `monitoring/prometheus.yml` 에 job 단위로 추가하고 `labels.service` 를 반드시 부여.
- 커스텀 메트릭 네이밍: counter 는 `snake_case_total`, gauge 는 `snake_case`. Tag cardinality 주의(userId, intentId 등 고유값 금지).
- compose 기본 `up` 에서는 observability 스택이 기동되지 않도록 `profiles: ["observability"]` 유지.

## 교차 도메인
- 다른 도메인 에이전트가 메트릭 추가를 요청하면: **네이밍·라벨·카디널리티만 승인**하고 실제 계측 코드는 해당 도메인에서 작성하도록 안내.
- `PaymentRecoveryService.payment_recovery_attempts_total` 처럼 도메인 쪽에 인라인 배치된 메트릭은 그대로 유지 — 중앙 집중이 오히려 결합도를 높인다.

## 주의
- prod 샘플링 1.0 금지 (성능·비용 악영향). 기본 0.1, 디버깅 시 한시적 상향 후 반드시 원복.
- `host.docker.internal` 스크레이프 타깃은 **로컬 호스트에서 서비스 기동 + Prometheus 만 Docker** 인 경우에만. 컴포즈 네트워크에선 서비스명(`monolith`, `qr-service`)을 사용.
- `/actuator/prometheus` 는 내부 전용. Nginx/LB 에서 외부 경로 매핑 금지.
- Grafana 대시보드 JSON 을 저장할 때는 `monitoring/grafana/provisioning/` 하위에 두고 compose 볼륨으로 마운트하도록 제안.
