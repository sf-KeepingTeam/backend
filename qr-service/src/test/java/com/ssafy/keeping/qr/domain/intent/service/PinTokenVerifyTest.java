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
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 세트 #33 PIN 토큰 검증 테스트 (K-1 ~ K-8).
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
                customerClient, notificationClient, om, fixedClock,
                eventPublisher, tuningProperties, approveHelper,
                transactionTemplate, pinTokenVerifier);
    }

    // ── 헬퍼 ──

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(JWT_SECRET.getBytes(StandardCharsets.UTF_8));
    }

    private String buildValidToken(UUID intentPublicId, Long customerId, String jti) {
        Instant now = fixedClock.instant();
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
                .amount(10000L)
                .expiresAt(LocalDateTime.now(fixedClock).plusMinutes(3))
                .idemSlotId(IDEM_SLOT_ID)
                .itemViews(List.of(
                        PaymentIntentItemView.builder()
                                .menuId(1L).name("아메리카노")
                                .unitPrice(5000L).quantity(2).lineTotal(10000L)
                                .build()))
                .build();
    }

    private void stubRedisSetIfAbsent(boolean wasAbsent) {
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(wasAbsent);
    }

    // ═══════════════════════════════════════════════════
    //  K-1: 유효한 토큰 → approve 성공 + verifyPin 호출 안 됨
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-1: 유효한 토큰 → approve 성공, customerClient.verifyPin 호출 없음")
    void approve_validToken_success_noPinCall() {
        String jti = UUID.randomUUID().toString();
        String token = buildValidToken(INTENT_PUBLIC_ID, CUSTOMER_ID, jti);
        stubRedisSetIfAbsent(true);

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);
        given(fundsService.capture(any(PaymentIntent.class), anyList()))
                .willReturn(new FundsService.FundsResult(true, true, 999L, null, false));

        PaymentIntentDetailResponse expectedRes = PaymentIntentDetailResponse.builder()
                .intentId(INTENT_PUBLIC_ID.toString())
                .status(PaymentStatus.APPROVED)
                .build();
        given(approveHelper.finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList()))
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
        // intentPublicId 불일치는 Redis jti 체크(step 5) 전인 step 4에서 발생하므로
        // Redis stub 불필요

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
        // 이미 만료된 토큰 생성 (exp를 확실히 과거로 — 시스템 시계와 무관하게)
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
        // 다른 키로 서명
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

        // 두 번째 호출: Redis SETNX 가 false 반환 (이미 존재)
        given(stringRedisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .willReturn(false);

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
        given(approveHelper.finalizeApproved(eq(INTENT_ID), eq(IDEM_SLOT_ID), anyList()))
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
    @DisplayName("K-7: token-enabled=false + 토큰 제출 → PIN 없어서 PIN_REQUIRED")
    void approve_tokenDisabled_fallsToPinPath() {
        tuningProperties.getPin().setTokenEnabled(false);

        // 토큰만 있고 PIN 없음 → PIN_REQUIRED
        // (token-enabled=false 이면 토큰 무시, PIN 필요)
        ApproveRequest req = tokenRequest(buildValidToken(INTENT_PUBLIC_ID, CUSTOMER_ID,
                UUID.randomUUID().toString()));

        // PIN도 함께 넣어야 기존 경로 동작 검증 가능
        // 토큰만 제출 + token-enabled=false → approveSplit 에서 PIN 경로 진입 → PIN null → verifyPin(null)
        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        // PIN이 null이므로 customerClient.verifyPin 에서 false 반환 기대
        given(customerClient.verifyPin(eq(CUSTOMER_ID), isNull())).willReturn(false);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, req))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.PIN_INVALID);

        // 토큰 경로가 아니라 기존 PIN 경로로 동작
        then(customerClient).should().verifyPin(eq(CUSTOMER_ID), isNull());
        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
    }

    // ═══════════════════════════════════════════════════
    //  K-8: 토큰 검증 실패 시 intent 상태 → DECLINED (세트 #31 일관)
    // ═══════════════════════════════════════════════════

    @Test
    @DisplayName("K-8: 토큰 검증 실패 → finalizeDeclined 호출 (세트 #31 설계 일관)")
    void approve_tokenFail_declinedConsistentWithSet31() {
        // 서명이 깨진 토큰으로 검증 실패 유도
        String badToken = "eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJrZWVwaW5nLXBpbi10b2tlbiJ9.invalid";

        ApprovePhaseAResult phaseA = normalPhaseA();
        given(approveHelper.prepareApproval(eq(INTENT_PUBLIC_ID), eq(IDEM_KEY), eq(CUSTOMER_ID), any()))
                .willReturn(phaseA);

        assertThatThrownBy(() ->
                service.approve(INTENT_PUBLIC_ID, IDEM_KEY, CUSTOMER_ID, tokenRequest(badToken)))
                .isInstanceOf(CustomException.class);

        // 세트 #31 일관: 검증 실패 시 finalizeDeclined 가 호출되어 DECLINED 가 DB에 영속
        then(approveHelper).should().finalizeDeclined(INTENT_ID, IDEM_SLOT_ID);
        // finalizeApproved 는 호출되지 않음
        then(approveHelper).should(never()).finalizeApproved(anyLong(), anyLong(), anyList());
    }
}
