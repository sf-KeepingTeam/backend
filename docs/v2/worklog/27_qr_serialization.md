# 세트 #27 — QrToken 직렬화 비대칭 수정 (차단 버그)

## 변경 파일

| 파일 | +/- | 무엇을 왜 |
|---|---|---|
| `qr/domain/qr/model/QrToken.java` | +3 | `@JsonIgnore` + `@JsonIgnoreProperties(ignoreUnknown=true)` |
| `qr/config/ObjectMapperConfig.java` | +12 | `redisObjectMapper` 빈 추가 (FAIL_ON_UNKNOWN_PROPERTIES=false) |
| `qr/domain/qr/repository/QrTokenRedisRepository.java` | +8/-3 | `@Qualifier("redisObjectMapper")` 주입으로 전환 |
| `qr/domain/qr/CLAUDE.md` | +1 | 파생 getter 주의사항 추가 |
| `docs/failures.md` | +1 | 실패 기록 |
| `docs/v2/README.md` | +2 | v1 측정 비교 각주 |

## 수정 3층

- **근본**: `@JsonIgnore` on `QrToken.isExpired()` — JSON에 `expired`가 안 들어감
- **배포 안전**: `@JsonIgnoreProperties(ignoreUnknown = true)` on `QrToken` — 구버전 JSON 읽기 가능
- **재발 방지**: `redisObjectMapper` 분리 — Redis 경로만 관대, HTTP 계약은 엄격 유지

## 테스트 결과

```
BUILD SUCCESSFUL — QrTokenSerializationTest 10개 전부 통과
```

- S-1: redisMapper 왕복 — 모든 필드 복원 ✅
- S-2: 직렬화 JSON에 `"expired"` 키 없음 ✅ (primaryMapper, redisMapper 둘 다)
- S-3: `"expired": false` 포함 구버전 JSON 읽기 성공 ✅ (redisMapper + primaryMapper 둘 다)
- S-4: findByTokenId 경로 왕복 ✅
- S-5: findByWalletId 경로 왕복 ✅
- S-8: LocalDateTime 왕복 보존 ✅ (ISO 문자열, 타임스탬프 아님)

## 전수 점검 결과

| 모델 | 직렬화 방식 | 파생 메서드 | 판정 |
|---|---|---|---|
| QrToken | Jackson (`redisObjectMapper`) | `isExpired()` | ✅ 수정 완료 |
| QrScanSession | `@RedisHash` (MappingRedisConverter, 필드 기반) | `isExpired()` | ✅ 안전 — Jackson을 쓰지 않음 |
| Store/Menu 캐시 DTO | `GenericJackson2JsonRedisSerializer` | 파생 getter 0건 | ✅ 안전 |

## monolith 쪽 동일 패턴 (보고만, 수정 안 함)

- `SignupTicketService` — `opsForValue().set()` 사용. 저장 모델 확인 필요
- `RefreshTokenService` — `opsForValue().set()` 사용. 저장 모델 확인 필요
- 이번 범위 밖 (v2 원장 작업과 충돌 방지)

## 확인하지 못한 것

- S-6 (QrScanSession Redis 왕복): Testcontainers Redis 미구성으로 실제 왕복 미실행. MappingRedisConverter는 필드 기반이라 논리적으로 안전하지만 **왕복 테스트로 증명하지는 못했다**
- S-7 (Store/Menu 캐시 왕복): 동일 이유. 다만 파생 getter가 0건이라 위험 없음
- 로컬 E2E (POST /api/qr → scan): 서버 미기동 상태라 미확인
