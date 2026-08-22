# k6 부하테스트 시나리오

상황별로 3가지 시나리오 세트가 존재한다. 이름이 겹치는 파일이 있어 용도별로 디렉토리를 분리했다.

```
k6/
├── prepayment/              선결제(토스) API 단독 시나리오
├── performance-comparison/  3가지 캐시 모드(NONE/PULL/PUSH) 성능 비교
└── aws-loadtest/            AWS 3-서버 실측 부하테스트 (Circuit Breaker 검증 포함)
```

## prepayment/

선결제·예약·승인 API에 대한 기능·부하 시나리오.

| 파일 | 용도 |
|---|---|
| `prepayment.js` | 기본 선결제 호출 |
| `prepayment_reserve_test.js` | 예약 단계 breakpoint 테스트 |
| `prepayment_reserve_steady.js` | 예약 정상 부하 |
| `prepayment_reserve_confirm_e2e.js` | 예약 → 승인 E2E |

실행 예:
```bash
k6 run -e BASE_URL=http://localhost:8080 k6/prepayment/prepayment.js
```

## performance-comparison/

모놀리식에 배경 부하를 주는 상태에서 QR 결제 응답 시간을 측정, 캐시 모드별 개선 효과를 비교한다. `docs/portfolio/tech-highlights.md`의 캐시 성능 수치(Intent -76%, Approve -47%)가 이 시나리오에서 얻어졌다.

| 파일 | 용도 |
|---|---|
| `01-background-load.js` | 모놀리식에 배경 부하 생성 |
| `02-qr-payment-flow.js` | QR 결제 응답 측정 |
| `common.js` | 공통 설정 |
| `README.md` | 실행 가이드 |
| `result-template.md` | 결과 기록 템플릿 |

자세한 실행 방식은 하위 `README.md` 참조.

## aws-loadtest/

AWS 3-서버 환경(Nginx / Monolith / QR Service) 실측용. Circuit Breaker 상태 전이, 장애 주입 테스트까지 포함. 측정 결과는 `docs/portfolio/loadtest-results.md`에 기록됨.

| 파일 | 용도 |
|---|---|
| `background-load.js` / `qr-payment.js` / `common.js` | k6 시나리오 |
| `chaos-test.sh` | 장애 주입 (monolith 일시 중단 등) |
| `monitor-cb.sh` | Resilience4j Circuit Breaker 상태 모니터링 |
| `run-all-modes.sh` | NONE/PULL/PUSH 3모드 자동 실행 |
| `README.md` | 실행 가이드 |

> 이 세트에서 쓰인 3-서버 실제 인프라 구성(server1-nginx, server2-monolith, server3-qr의 compose·nginx conf)은 `docs/archive/migration/loadtest/`에 보존되어 있음.

## 공통 규칙

- 기본 환경변수: `BASE_URL` (예: `http://localhost:80` 또는 EC2 퍼블릭 IP)
- 부하테스트용 인증 우회: `LOADTEST_BACKDOOR_ENABLED=true` 로 서버를 띄우고 `X-Test-User-Id` / `X-Test-User-Role` 헤더 전송
- 결과 저장: `--out json=results/xxx.json`
