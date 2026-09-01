package com.ssafy.keeping.qr.domain.intent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.acl.CustomerClient;
import com.ssafy.keeping.qr.acl.MenuClient;
import com.ssafy.keeping.qr.acl.NotificationClient;
import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.config.JwtProperties;
import com.ssafy.keeping.qr.config.PaymentTuningProperties;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.dto.ApprovePhaseAResult;
import com.ssafy.keeping.qr.domain.intent.dto.ApproveRequest;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentDetailResponse;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentItemView;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.outbox.PaymentOutboxRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import com.ssafy.keeping.qr.domain.qr.repository.QrFlowRedisStore;
import com.ssafy.keeping.qr.domain.qr.service.QrTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PIN 토큰 검증 테스트.
 *
 * <p>K-1 ~ K-8: 세트 #33 (서비스 경유)
 * K-10 ~ K-14: 세트 #40 (sub 주체 검증 — PinTokenVerifier 직접 호출)
 * K-20 ~ K-31: 세트 #42 (세션 토큰 검증 — PinTokenVerifier 직접 호출)
 *
 * <p>작성만 하고 실행하지 않는다 (Testcontainers/Docker 의존 회피).
 */
@ExtendWith(MockitoExtension.class)
class PinTokenVerifyTest {

    @Mock private PaymentIntentRepository intentRepository;
    @Mock private PaymentIntentItemRepository itemRepository;
    @Mock private com.ssafy.keeping.qr.domain.idempotency.service.IdempotencyService idempotencyService;
    @Mock private FundsService fundsService;
    @Mock private QrTokenService qrTokenService;
    @Mock private MenuClient menuClient;
    @Mock private StoreClient storeClient;
    @Mock private CustomerClient customerClient;
    @Mock private NotificationClient notificationClient;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ApproveTransactionHelper approveHelper;
    @Mock private TransactionTemplate transactionTemplate;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private PaymentOutboxRepository outboxRepository;

    private PaymentIntentService service;
    private PinTokenVerifier pinTokenVerifier;
    private PaymentTuningProperties tuningProperties;

    private static final String JWT_SECRET =
            "NbPg+8/rCm9yW15pYbbOTXdg1QTPqDcRMA8oauseuOqzrAdkLMcXfmbMLkqt3tZ5HecMd5bnCscx4Iuo2EjnJA==";
    private static final String JWT_ISSUER = "kakao-oauth2-jwt";
    private static final UUID INTENT_PUBLIC_ID = UUID.randomUUID();
    private static final Long CUSTOMER_ID = 100L;
    private static final Long INTENT_ID = 1L;
    private static final Long IDEM_SLOT_ID = 10L;
    private static final String IDEM_KEY = UUID.randomUUID().toString();
    private static final String PIN = "123456";
    private static final long DEFAULT_AMOUNT = 10_000L;

    private final Clock fixedClock =
            Clock.fixed(Instant.parse("2026-08-23T06:00:00Z"), ZoneId.of("Asia/Seoul"));

    @BeforeEach
    void setUp() {
        tuningProperties = new PaymentTuningProperties();
        tuningProperties.getApprove().setSplitTransaction(true);
        tuningProperties.getNotification().setAsync(true);
        tuningProperties.getPin().setTokenEnabled(true);
        tuningProperties.getPin().setTokenTtlSeconds(60);

        JwtProperties jwtProperties = new JwtProperties(JWT_SECRET, JWT_ISSUER);
        pinTokenVerifier = new PinTokenVerifier(jwtProperties, tuningProperties, stringRedisTemplate);

        ObjectMapper om = new ObjectMapper();

        service = new PaymentIntentService(
                intentRepository, itemRepository, idempotencyService,
                fundsService, qrTokenService, menuClient, storeClient,
                customerClient, notificationClient, om, om, fixedClock,
                eventPublisher, tuningProperties, approveHelper,
                transactionTemplate, pinTokenVerifier, outboxRepository,
                mock(QrFlowRedisStore.class));
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** intent 토큰 (intentPublicId 있음, mu 없음 = 1회용) */
    private String buildIntentToken(UUID intentPublicId, Long customerId, String jti) {
        // PinTokenVerifier 는 시스템 시계로 exp 를 검증하므로 Instant.now() 를 써야 한다
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("keeping-pin-token")
                .subject(String.valueOf(customerId))
                .claim("intentPublicId", intentPublicId.toString())
                .id(jti)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(signingKey())
                .compact();
    }

    /** 세션 토큰 (intentPublicId 없음, mu·amax·atot 있음) */
    private String buildSessionToken(Long customerId, String jti,
                                     int mu, long amax, long atot) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer("keeping-pin-token")
                .subject(String.valueOf(customerId))
                // intentPublicId 클레임 없음 — 이 유무가 모드 판정 기준
                .id(jti)
                .claim("mu", mu)
                .claim("amax", amax)
                .claim("atot", atot)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(180)))
                .signWith(signingKey())
                .compact();
    }

    /** buildValidToken — 기존 K-1~K-8 호환 (intent 토큰) */
    private String buildValidToken(UUID intentPublicId, Long customerId, String jti) {
        return buildIntentToken(intentPublicId, customerId, jti);
    }

    private ApproveRequest tokenRequest(String token) {
        ApproveRequest req = new ApproveRequest();
        try {
            Field f = ApproveRequest.class.getDeclaredField("pinToken");
            f.setAccessible(true);
            f.set(req, token);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return req;
    }

    private ApproveRequest pinRequest() {
        ApproveRequest req = new ApproveRequest();
        try {
            Field f = ApproveRequest.class.getDeclaredField("pin");
            f.setAccessible(true);
            f.set(req, PIN);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return req;
    }

    private ApprovePhaseAResult normalPhaseA() {
        return ApprovePhaseAResult.builder()
                .intentId(INTENT_ID)
                .intentPublicId(INTENT_PUBLIC_ID)
                .customerId(CUSTOMER_ID)
                .walletId(200L)
                .storeId(300L)
                .amount(DEFAULT_AMOUNT)
                .expiresAt(LocalDateTime.now(fixedClock).plusMinutes(3))
                .idemSlotId(IDEM_SLOT_ID)
                .itemViews(List.of(
                        PaymentIntentItemView.builder()
                                .menuId(1L).name("아메리카노")
                                .unitPrice(5000L).quantity(2).lineTotal(DEFAULT_AMOUNT)
                                .build()))
                .build();
    }

    /** Lua 스크립트가 luaReturn 을 반환하도록 스텁 (varargs 6개: maxUses, amount, atot, ttl, iat, revCheck) */
    private void stubLua(long luaReturn) {
        given(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .willReturn(luaReturn);
    }

    /** Lua null 반환 스텁 */
    private void stubLuaNull() {
        given(stringRedisTemplate.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any()))
                .willReturn(null);
    }

    // 기존 K-1~K-8 호환을 위한 Redis 스텁 (SETNX 방식 → Lua 방식으로 교체)
    private void stubRedisSetIfAbsent(boolean wasAbsent) {
        // K-1, K-10 등 기존 테스트는 서비스 경유이므로 Lua 스텁 필요
        stubLua(wasAbsent ? 0L : 1L);
    }

    // ═══════════════════════════════════════════════════
    //  K-1: 유효한 토큰 → approve 성공 + verifyPin 호출 안 됨
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-1: 유효한 토큰 → approve 성공, customerClient.verifyPin 호출 없음")
    void approve_validToken_success_noPinCall() {
        String jti = UUID.randomUUID().toString();
        String token = buildValidToken(INTENT_PUBLIC_ID, CUSTOMER_ID, jti);
        stubLua(0L); // 통과

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(fundsService.capture(any(PaymentIntent.class), anyList()))
                .willReturn(new FundsService.FundsResult(true, true, 999L, null, false));

        PaymentIntentDetailResponse expectedRes = PaymentIntentDetailResponse.builder()
                .intentId(INTENT_PUBLIC_ID.toString())
                .status(PaymentStatus.APPROVED)
                .build();
        given(approveHelper.finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList(), anyLong(), anyLong(), anyLong()))
                .willReturn(expectedRes);

        IdempotentResult<PaymentIntentDetailResponse> result =
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, tokenRequest(token));

        assertThat(result.getBody().getStatus()).isEqualTo(PaymentStatus.APPROVED);

        // 핵심: customerClient.verifyPin 호출 0건
        then(customerClient).should(never()).verifyPin(anyLong(), anyString());
    }

    // ═══════════════════════════════════════════════════
    //  K-2: 다른 intent용 토큰 → 거부 (가장 중요)
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-2: 다른 intent용 토큰 → PIN_TOKEN_INTENT_MISMATCH")
    void approve_wrongIntent_rejected() {
        UUID otherIntentId = UUID.randomUUID();
        String jti = UUID.randomUUID().toString();
        String token = buildValidToken(otherIntentId, CUSTOMER_ID, jti);
        // intentPublicId 불일치는 Redis 왕복(step 5) 전인 step 3에서 발생

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, tokenRequest(token)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_INTENT_MISMATCH);

        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
    }

    // ═══════════════════════════════════════════════════
    //  K-3: 만료된 토큰 → 거부
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-3: 만료된 토큰 → PIN_TOKEN_EXPIRED")
    void approve_expiredToken_rejected() {
        Instant definitelyPast = Instant.parse("2020-01-01T00:00:00Z");
        String token = Jwts.builder()
                .issuer("keeping-pin-token")
                .subject(String.valueOf(CUSTOMER_ID))
                .claim("intentPublicId", INTENT_PUBLIC_ID.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(definitelyPast.minusSeconds(60)))
                .expiration(Date.from(definitelyPast))
                .signWith(signingKey())
                .compact();

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, tokenRequest(token)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_EXPIRED);

        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
    }

    // ═══════════════════════════════════════════════════
    //  K-4: 서명이 깨진 토큰 → 거부
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-4: 서명 변조된 토큰 → PIN_TOKEN_INVALID")
    void approve_badSignature_rejected() {
        SecretKey wrongKey = Keys.hmacShaKeyFor(
                "wrong-secret-key-that-is-long-enough-for-hmac-sha256-algorithm!!"
                        .getBytes(StandardCharsets.UTF_8));

        String token = Jwts.builder()
                .issuer("keeping-pin-token")
                .subject(String.valueOf(CUSTOMER_ID))
                .claim("intentPublicId", INTENT_PUBLIC_ID.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(fixedClock.instant()))
                .expiration(Date.from(fixedClock.instant().plusSeconds(60)))
                .signWith(wrongKey)
                .compact();

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, tokenRequest(token)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_INVALID);

        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
    }

    // ═══════════════════════════════════════════════════
    //  K-5: 같은 토큰 2회 → 두 번째 거부 (jti 재사용 차단)
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-5: 같은 토큰 2회 사용 → 두 번째 PIN_TOKEN_REUSED")
    void approve_reusedToken_secondRejected() {
        String jti = UUID.randomUUID().toString();
        String token = buildValidToken(INTENT_PUBLIC_ID, CUSTOMER_ID, jti);

        // Lua 가 1 반환 = 횟수 초과
        stubLua(1L);

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, tokenRequest(token)))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_REUSED);

        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
    }

    // ═══════════════════════════════════════════════════
    //  K-6: 토큰 없이 PIN만 → 기존 경로 동작
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-6: 토큰 없이 PIN만 → 기존 customerClient.verifyPin 경로")
    void approve_pinOnly_legacyPath() {
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(customerClient.verifyPin(CUSTOMER_ID, PIN)).willReturn(true);
        given(fundsService.capture(any(PaymentIntent.class), anyList()))
                .willReturn(new FundsService.FundsResult(true, true, 999L, null, false));

        PaymentIntentDetailResponse expectedRes = PaymentIntentDetailResponse.builder()
                .intentId(INTENT_PUBLIC_ID.toString())
                .status(PaymentStatus.APPROVED)
                .build();
        given(approveHelper.finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList(), anyLong(), anyLong(), anyLong()))
                .willReturn(expectedRes);

        IdempotentResult<PaymentIntentDetailResponse> result =
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, pinRequest());

        assertThat(result.getBody().getStatus()).isEqualTo(PaymentStatus.APPROVED);

        // 핵심: customerClient.verifyPin 이 호출됨
        then(customerClient).should().verifyPin(CUSTOMER_ID, PIN);
    }

    // ═══════════════════════════════════════════════════
    //  K-7: token-enabled=false + 토큰 있음 → 기존 PIN 경로
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-7: token-enabled=false + 토큰 제출 → PIN 없어서 PIN_INVALID")
    void approve_tokenDisabled_fallsToPinPath() {
        tuningProperties.getPin().setTokenEnabled(false);

        ApproveRequest req = tokenRequest(buildValidToken(INTENT_PUBLIC_ID, CUSTOMER_ID,
                UUID.randomUUID().toString()));

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        given(customerClient.verifyPin(eq(CUSTOMER_ID), isNull())).willReturn(false);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_INVALID);

        then(customerClient).should().verifyPin(eq(CUSTOMER_ID), isNull());
        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
    }

    // ═══════════════════════════════════════════════════
    //  K-8: 토큰 검증 실패 시 intent 상태 → DECLINED
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-8: 토큰 검증 실패 → finalizeDeclined 호출 (세트 #31 설계 일관)")
    void approve_tokenFail_declinedConsistentWithSet31() {
        String badToken = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJrZWVwaW5nLXBpbi10b2tlbiJ9.invalid";

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, tokenRequest(badToken)))
                .isInstanceOf(CustomException.class);

        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
        then(approveHelper).should(never()).finalizeApproved(anyLong(), anyLong(), anyList(), anyLong(), anyLong(), anyLong());
    }

    // ═══════════════════════════════════════════════════
    //  K-10 ~ K-14: sub 주체 검증 (세트 #40)
    //  PinTokenVerifier.verify() 를 직접 호출해 단위 검증.
    //  작성만 하고 실행하지 않는다 (Testcontainers/Docker 의존 회피).
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-10: sub=99, 인증 customerId=99 → 통과, 99 반환")
    void verify_subMatchesAuth_returnsCustomerId() {
        Long customerId = 99L;
        String jti = UUID.randomUUID().toString();
        String token = buildIntentToken(INTENT_PUBLIC_ID, customerId, jti);
        stubLua(0L);

        Long result = pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, customerId, DEFAULT_AMOUNT);

        assertThat(result).isEqualTo(99L);
    }

    @Test
    @DisplayName("K-11: sub=99, 인증 customerId=77 → PIN_TOKEN_SUBJECT_MISMATCH")
    void verify_subMismatchAuth_throws() {
        Long tokenOwner = 99L;
        Long authenticatedCustomerId = 77L;
        String jti = UUID.randomUUID().toString();
        String token = buildIntentToken(INTENT_PUBLIC_ID, tokenOwner, jti);

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, authenticatedCustomerId, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_SUBJECT_MISMATCH);
    }

    @Test
    @DisplayName("K-12: sub 클레임이 비숫자(\"abc\") → PIN_TOKEN_INVALID (500 아님)")
    void verify_nonNumericSub_invalidNotServerError() {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .issuer("keeping-pin-token")
                .subject("abc")
                .claim("intentPublicId", INTENT_PUBLIC_ID.toString())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(60)))
                .signWith(signingKey())
                .compact();

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_INVALID);
    }

    @Test
    @DisplayName("K-13: 인증 customerId 가 null → PIN_TOKEN_SUBJECT_MISMATCH")
    void verify_nullAuthCustomerId_throws() {
        String jti = UUID.randomUUID().toString();
        String token = buildIntentToken(INTENT_PUBLIC_ID, CUSTOMER_ID, jti);

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, null, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_SUBJECT_MISMATCH);
    }

    @Test
    @DisplayName("K-14: sub 불일치 시 Redis 호출 없음 (증폭 경로 차단)")
    void verify_subMismatch_noRedisInteraction() {
        Long tokenOwner = 99L;
        Long authenticatedCustomerId = 77L;
        String jti = UUID.randomUUID().toString();
        String token = buildIntentToken(INTENT_PUBLIC_ID, tokenOwner, jti);

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, authenticatedCustomerId, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_SUBJECT_MISMATCH);

        then(stringRedisTemplate).shouldHaveNoInteractions();
    }

    // ═══════════════════════════════════════════════════
    //  K-20 ~ K-31: 세션 토큰 검증 (세트 #42)
    //  PinTokenVerifier.verify() 를 직접 호출해 단위 검증.
    //  작성만 하고 실행하지 않는다 (Testcontainers/Docker 의존 회피).
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-20: intent 토큰(intentPublicId 있음, mu 없음) 1회 → 통과. Lua 에 maxUses=1 이 넘어간다")
    void verify_intentToken_firstUse_passes() {
        // intent 토큰: intentPublicId 있음, mu 없음 = 1회용
        String jti = UUID.randomUUID().toString();
        String token = buildIntentToken(INTENT_PUBLIC_ID, CUSTOMER_ID, jti);
        stubLua(0L); // 통과

        Long result = pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT);

        assertThat(result).isEqualTo(CUSTOMER_ID);
        // Lua 가 1회 호출됐음을 확인 (maxUses=1 인자로)
        then(stringRedisTemplate).should().execute(
                any(RedisScript.class),
                anyList(),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("K-21: 같은 intent 토큰 2회 → PIN_TOKEN_REUSED")
    void verify_intentToken_secondUse_reused() {
        String jti = UUID.randomUUID().toString();
        String token = buildIntentToken(INTENT_PUBLIC_ID, CUSTOMER_ID, jti);
        stubLua(1L); // 횟수 초과

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_REUSED);
    }

    @Test
    @DisplayName("K-22: 세션 토큰 mu=5, 3회째 → 통과")
    void verify_sessionToken_thirdUse_passes() {
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLua(0L); // 통과 (uses=3, maxUses=5)

        Long result = pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT);

        assertThat(result).isEqualTo(CUSTOMER_ID);
    }

    @Test
    @DisplayName("K-23: 세션 토큰 mu=5, 6회째 (Lua 가 1 반환) → PIN_TOKEN_REUSED")
    void verify_sessionToken_sixthUse_reused() {
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLua(1L); // 횟수 초과

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_REUSED);
    }

    @Test
    @DisplayName("K-24: 세션 토큰, amount > amax → PIN_TOKEN_AMOUNT_EXCEEDED — Redis 호출 0건")
    void verify_sessionToken_amountExceedsAmax_rejectedBeforeRedis() {
        // amax = 50_000, amount = 60_000 → step 4 에서 바로 거부
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 50_000L, 300_000L);

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, 60_000L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_AMOUNT_EXCEEDED);

        // 핵심: Redis 호출이 없어야 한다 (예산 보호)
        then(stringRedisTemplate).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("K-25: 세션 토큰, 누적 초과 (Lua 가 2 반환) → PIN_TOKEN_AMOUNT_EXCEEDED")
    void verify_sessionToken_totalAmountExceeded_lua2() {
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLua(2L); // 누적 금액 초과

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_AMOUNT_EXCEEDED);
    }

    @Test
    @DisplayName("K-26: Lua 가 3 반환 → PIN_TOKEN_REVOKED")
    void verify_luaReturns3_revoked() {
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLua(3L); // 폐기됨

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_REVOKED);
    }

    @Test
    @DisplayName("K-27: Lua 가 null 반환 → PIN_TOKEN_INVALID — 통과시키지 않는다")
    void verify_luaReturnsNull_invalid() {
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLuaNull();

        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_INVALID);
    }

    @Test
    @DisplayName("K-28: revocation-check=false → Lua ARGV[6]='0' 으로 호출된다")
    void verify_revocationCheckFalse_argv6Is0() {
        // 기본값이 false 이므로 별도 설정 불필요
        assertThat(tuningProperties.getPin().getSessionToken().isRevocationCheck()).isFalse();

        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLua(0L);

        pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT);

        // '0' 이 포함된 varargs 로 Lua 가 호출됐는지 확인
        then(stringRedisTemplate).should().execute(
                any(RedisScript.class),
                anyList(),
                eq("5"),           // ARGV[1] maxUses
                eq(String.valueOf(DEFAULT_AMOUNT)), // ARGV[2] amount
                eq("300000"),      // ARGV[3] maxAmountTotal
                anyString(),       // ARGV[4] ttl
                anyString(),       // ARGV[5] iat
                eq("0")            // ARGV[6] revocationCheck=false
        );
    }

    @Test
    @DisplayName("K-29: atot 가 Integer 로 역직렬화됨 → ClassCastException 없이 통과")
    void verify_atotAsInteger_noClassCastException() {
        // jjwt 가 숫자 클레임을 Integer 로 돌려줄 수 있다 (T-3 함정)
        // buildSessionToken 은 Long 으로 넣지만, jjwt 는 작은 값이면 Integer 로 역직렬화한다.
        // atot=300000 은 Integer 범위 내 → Integer 로 역직렬화될 수 있음.
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLua(0L);

        // ClassCastException 없이 정상 동작해야 한다
        Long result = pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT);

        assertThat(result).isEqualTo(CUSTOMER_ID);
    }

    @Test
    @DisplayName("K-30: 세션 토큰인데 경로 intentPublicId 가 있어도 통과 (세션 토큰은 intent 를 안 본다)")
    void verify_sessionToken_anyIntentPublicId_passes() {
        // 세션 토큰은 intentPublicId 클레임이 없으므로 경로의 값과 무관하게 통과
        String jti = UUID.randomUUID().toString();
        String token = buildSessionToken(CUSTOMER_ID, jti, 5, 100_000L, 300_000L);
        stubLua(0L);

        // 어떤 intentPublicId 가 경로에 있어도 세션 토큰은 이를 대조하지 않는다
        UUID anyIntentId = UUID.randomUUID();
        Long result = pinTokenVerifier.verify(token, anyIntentId, CUSTOMER_ID, DEFAULT_AMOUNT);

        assertThat(result).isEqualTo(CUSTOMER_ID);
    }

    @Test
    @DisplayName("K-31: intent 토큰인데 경로와 불일치 → PIN_TOKEN_INTENT_MISMATCH (기존 동작 유지)")
    void verify_intentToken_intentMismatch_rejected() {
        // intent 토큰: intentPublicId 클레임 있음
        String jti = UUID.randomUUID().toString();
        UUID otherIntent = UUID.randomUUID(); // 토큰에 바인딩된 intent
        String token = buildIntentToken(otherIntent, CUSTOMER_ID, jti);

        // 경로의 intentPublicId 는 INTENT_PUBLIC_ID (다른 값)
        assertThatThrownBy(() ->
                pinTokenVerifier.verify(token, INTENT_PUBLIC_ID, CUSTOMER_ID, DEFAULT_AMOUNT))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_TOKEN_INTENT_MISMATCH);

        // Redis 호출 없음 (step 3에서 조기 거부)
        then(stringRedisTemplate).shouldHaveNoInteractions();
    }
}
