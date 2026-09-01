-- ═══════════════════════════════════════════════════════════════════════════
--  A2 측정용 — PIN 해시를 "약한 Argon2" 로 교체한다
-- ═══════════════════════════════════════════════════════════════════════════
--
--  ★ 왜 코드 수정 없이 되는가
--
--  Spring Security 의 Argon2PasswordEncoder.matches() 는
--  **인코더 빈의 설정값을 쓰지 않는다.** 저장된 해시 문자열에 박혀 있는
--  파라미터(m/t/p)를 파싱해서 그대로 재현한다.
--
--      Argon2EncodingUtils.decode(encodedPassword)  →  parameters
--      generator.init(decoded.getParameters())      →  이 값으로 계산
--
--  그리고 PinAuthService.verify() 는 성공해도 해시를 다시 쓰지 않는다
--  (matches() 만 호출, upgradeEncoding 없음). 즉 한 번 바꾸면 유지된다.
--
--  → **PasswordConfig 를 건드리지 않고 이 테이블만 갈아끼우면
--     PIN 검증 비용을 원하는 만큼 낮출 수 있다.**
--
--  ★ 이건 "Argon2 를 빼자" 는 게 아니다
--
--  알고리즘 선택은 옳다(OWASP 1순위). 측정에서 **변인을 하나 줄이기 위한**
--  임시 조치이며, 최종 결론은 운영값(A0/A1) 기준으로 적는다.
--  측정이 끝나면 argon2-strong.sql 로 반드시 원복한다.
--
--  평문 PIN 은 그대로 "123456" 이다. 바뀌는 건 계산 비용뿐이다.
--
--      운영값  m=8192KB, t=3, p=1
--      약화값  m=64KB,   t=1, p=1      (메모리 1/128, 패스 1/3)
--
-- ═══════════════════════════════════════════════════════════════════════════

USE ssafy_fintech_db;

UPDATE customer_pin_auth
SET pin_hash = '$argon2id$v=19$m=64,t=1,p=1$S2VlcGluZ0xvYWRUZXN0IQ$WzLn71fP0e610jegbun6f37RI0bUzmf09YigC1fJe3Q',
    failed_count = 0,
    locked_until = NULL;

-- 확인: 전부 m=64 여야 한다
SELECT SUBSTRING_INDEX(SUBSTRING_INDEX(pin_hash, '$', 4), '$', -1) AS params,
       COUNT(*) AS cnt
FROM customer_pin_auth
GROUP BY params;
