package com.ssafy.keeping.qr.domain.intent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.acl.CustomerClient;
import com.ssafy.keeping.qr.acl.MenuClient;
import com.ssafy.keeping.qr.acl.NotificationClient;
import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.acl.dto.MenuResponse;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import com.ssafy.keeping.qr.common.IdUtil;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.qr.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.qr.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.qr.domain.intent.canonical.CanonicalApprove;
import com.ssafy.keeping.qr.domain.intent.canonical.CanonicalInitiate;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.dto.*;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntentItem;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import com.ssafy.keeping.qr.config.PaymentTuningProperties;
import com.ssafy.keeping.qr.domain.intent.event.PaymentApprovedEvent;
import com.ssafy.keeping.qr.domain.intent.event.PaymentRequestedEvent;
import com.ssafy.keeping.qr.domain.qr.model.QrScanSession;
import com.ssafy.keeping.qr.domain.qr.service.QrTokenService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Slf4j
@Service
public class PaymentIntentService {

  private final PaymentIntentRepository intentRepository;
  private final PaymentIntentItemRepository itemRepository;
  private final IdempotencyService idempotencyService;
  private final FundsService fundsService;
  private final QrTokenService qrTokenService;
  private final MenuClient menuClient;
  private final StoreClient storeClient;
  private final CustomerClient customerClient;
  private final NotificationClient notificationClient;
  private final ObjectMapper canonicalObjectMapper;
  private final Clock clock;
  private final ApplicationEventPublisher eventPublisher;
  private final PaymentTuningProperties tuningProperties;
  private final ApproveTransactionHelper approveHelper;
  private final TransactionTemplate transactionTemplate;
  private final PinTokenVerifier pinTokenVerifier;

  public PaymentIntentService(
      PaymentIntentRepository intentRepository,
      PaymentIntentItemRepository itemRepository,
      IdempotencyService idempotencyService,
      FundsService fundsService,
      QrTokenService qrTokenService,
      MenuClient menuClient,
      StoreClient storeClient,
      CustomerClient customerClient,
      NotificationClient notificationClient,
      @Qualifier("canonicalObjectMapper") ObjectMapper canonicalObjectMapper,
      Clock clock,
      ApplicationEventPublisher eventPublisher,
      PaymentTuningProperties tuningProperties,
      ApproveTransactionHelper approveHelper,
      TransactionTemplate transactionTemplate,
      PinTokenVerifier pinTokenVerifier) {
    this.intentRepository = intentRepository;
    this.itemRepository = itemRepository;
    this.idempotencyService = idempotencyService;
    this.fundsService = fundsService;
    this.qrTokenService = qrTokenService;
    this.menuClient = menuClient;
    this.storeClient = storeClient;
    this.customerClient = customerClient;
    this.notificationClient = notificationClient;
    this.canonicalObjectMapper = canonicalObjectMapper;
    this.clock = clock;
    this.eventPublisher = eventPublisher;
    this.tuningProperties = tuningProperties;
    this.approveHelper = approveHelper;
    this.transactionTemplate = transactionTemplate;
    this.pinTokenVerifier = pinTokenVerifier;
  }

  /** 결제 의도 생성 세션 토큰 기반 (QR 스캔 후 발급받은 토큰) */
  @Transactional
  public IdempotentResult<PaymentIntentDetailResponse> initiate(
      String sessionToken, String idempotencyKeyHeader, Long ownerId, PaymentInitiateRequest req) {

    if (req == null || req.getOrderItems() == null || req.getOrderItems().isEmpty()) {
      throw new CustomException(ErrorCode.PAYMENT_INIT_ORDER_EMPTY);
    }
    if (req.getStoreId() == null) {
      throw new CustomException(ErrorCode.PAYMENT_INIT_STORE_ID_REQUIRED);
    }
    if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
      throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    // 멱등 바디 정규화 → SHA-256
    String canonicalBody = canonicalizeInitiateBody(req);
    byte[] bodyHash = IdempotencyService.sha256(canonicalBody);

    // 멱등 선점 또는 로드
    UUID keyUuid = UUID.fromString(idempotencyKeyHeader);
    String path = "/cpqr/" + sessionToken + "/initiate";
    IdemBegin begin =
        idempotencyService.beginOrLoad(
            IdemActorType.MERCHANT, ownerId, "POST", path, keyUuid, bodyHash);

    IdempotencyKey slot = begin.getRow();

    // 본문 충돌 확인
    if (idempotencyService.isBodyConflict(slot, bodyHash)) {
      throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
    }

    if (slot.getStatus() == IdemStatus.DONE) {
      PaymentIntentDetailResponse replay;
      try {
        var node = slot.getResponseJson();
        if (node != null && !node.isNull()) {
          replay = canonicalObjectMapper.treeToValue(node, PaymentIntentDetailResponse.class);
        } else if (slot.getIntentPublicId() != null) {
          replay = rebuildFromResource(slot.getIntentPublicId());
        } else {
          throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
        }
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new CustomException(ErrorCode.JSON_PARSE_ERROR);
      }
      return IdempotentResult.okReplay(replay);
    }

    if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
      return IdempotentResult.acceptedWithRetryAfterSeconds(2);
    }

    // 세션 토큰 검증 - 자체 Redis에서 조회 (QR 스캔 후 발급된 세션)
    QrScanSession session =
        qrTokenService
            .findSession(sessionToken)
            .orElseThrow(() -> new CustomException(ErrorCode.SESSION_NOT_FOUND));

    LocalDateTime now = LocalDateTime.now(clock);

    if (session.getExpiresAt() != null && now.isAfter(session.getExpiresAt())) {
      throw new CustomException(ErrorCode.SESSION_EXPIRED);
    }
    if (!Objects.equals(session.getBindStoreId(), req.getStoreId())) {
      throw new CustomException(ErrorCode.QR_STORE_MISMATCH);
    }

    // 메뉴 로딩/검증
    Set<Long> uniqueMenuIds =
        req.getOrderItems().stream()
            .map(PaymentInitiateItemDto::getMenuId)
            .collect(Collectors.toSet());

    List<MenuResponse> menus = menuClient.getMenus(new ArrayList<>(uniqueMenuIds));
    if (menus.size() != uniqueMenuIds.size()) {
      throw new CustomException(ErrorCode.MENU_NOT_FOUND);
    }

    for (MenuResponse m : menus) {
      if (!Objects.equals(m.getStoreId(), req.getStoreId())) {
        throw new CustomException(ErrorCode.MENU_CROSS_STORE_CONFLICT);
      }
      if (!m.isActive() || m.isSoldOut()) {
        throw new CustomException(ErrorCode.MENU_UNAVAILABLE);
      }
    }

    Map<Long, MenuResponse> menuById =
        menus.stream().collect(Collectors.toMap(MenuResponse::getMenuId, m -> m));

    // 합계 계산 (quantity 검증은 canonicalizeInitiateBody에서 완료됨)
    long total = 0L;
    for (PaymentInitiateItemDto item : req.getOrderItems()) {
      MenuResponse m = menuById.get(item.getMenuId());
      total += (long) m.getPrice() * item.getQuantity();
    }

    // Intent 생성
    PaymentIntent intent =
        PaymentIntent.builder()
            .publicId(IdUtil.newUuidV7())
            .qrTokenId(sessionToken)
            .customerId(session.getCustomerId())
            .walletId(session.getWalletId())
            .storeId(req.getStoreId())
            .amount(total)
            .status(PaymentStatus.PENDING)
            .createdAt(now)
            .updatedAt(now)
            .expiresAt(now.plusMinutes(3))
            .idempotencyKey(idempotencyKeyHeader)
            .build();

    intent = intentRepository.save(intent);

    // 아이템 스냅샷 저장
    List<PaymentIntentItem> items = new ArrayList<>();
    for (PaymentInitiateItemDto item : req.getOrderItems()) {
      MenuResponse m = menuById.get(item.getMenuId());
      PaymentIntentItem row =
          PaymentIntentItem.builder()
              .intent(intent)
              .menuId(m.getMenuId())
              .menuNameSnap(m.getMenuName())
              .unitPriceSnap(m.getPrice())
              .quantity(item.getQuantity())
              .build();
      items.add(row);
    }
    itemRepository.saveAll(items);

    // 응답 구성
    List<PaymentIntentItemView> itemViews =
        items.stream().map(this::toItemView).collect(Collectors.toList());

    PaymentIntentDetailResponse res = PaymentIntentDetailResponse.from(intent, itemViews);

    // 결제 요청 알림
    if (tuningProperties.getNotification().isAsync()) {
      // 비동기: 커밋 후 리스너에서 발송 (DB 커넥션 점유 없음)
      eventPublisher.publishEvent(new PaymentRequestedEvent(
          intent.getIntentId(), session.getCustomerId(),
          intent.getStoreId(), intent.getAmount()));
    } else {
      // 동기 폴백 (payment.notification.async=false)
      try {
        StoreResponse store = storeClient.getStore(intent.getStoreId()).orElse(null);
        String storeName = store != null ? store.getStoreName() : "매장";

        String notificationContent =
            String.format("%s에서 %,d원 결제 요청이 도착했습니다.", storeName, intent.getAmount());
        notificationClient.sendToCustomer(
            session.getCustomerId(), "PAYMENT_REQUEST", notificationContent);
        log.info("결제 요청 알림 전송 완료 - 손님ID: {}, 결제 금액: {}",
            session.getCustomerId(), intent.getAmount());
      } catch (Exception e) {
        log.warn("결제 요청 알림 전송 실패 - 손님ID: {}, error: {}",
            session.getCustomerId(), e.getMessage());
      }
    }

    // 멱등 완료 기록
    idempotencyService.complete(slot, HttpStatus.CREATED.value(), res, intent.getPublicId());

    return IdempotentResult.created(res);
  }

  @Transactional(readOnly = true)
  public PaymentIntentDetailResponse getDetail(UUID intentPublicId) {
    PaymentIntent intent =
        intentRepository
            .findByPublicId(intentPublicId)
            .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));
    List<PaymentIntentItem> rows = itemRepository.findByIntent_IntentId(intent.getIntentId());
    List<PaymentIntentItemView> itemViews =
        rows.stream().map(this::toItemView).collect(Collectors.toList());
    return PaymentIntentDetailResponse.from(intent, itemViews);
  }

  /**
   * 결제 승인 -- 플래그에 따라 레거시/분리 경로 디스패치.
   *
   * <p>{@code payment.approve.split-transaction=true} (기본)이면
   * TX-A / NO-TX / TX-B 로 트랜잭션을 분리해 DB 커넥션 점유 시간을 단축한다.
   * {@code false}이면 기존 단일 트랜잭션 경로로 동작한다.
   */
  public IdempotentResult<PaymentIntentDetailResponse> approve(
      UUID intentPublicId, String idempotencyKeyHeader, Long customerId, ApproveRequest req) {

    // 공통 입력 검증 (트랜잭션 불필요)
    if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
      throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
    }

    // PIN 또는 PIN 토큰 중 하나 필수
    boolean hasPin = req != null && req.getPin() != null && !req.getPin().isBlank();
    boolean hasToken = req != null && req.getPinToken() != null && !req.getPinToken().isBlank();
    if (!hasPin && !hasToken) {
      throw new CustomException(ErrorCode.PIN_REQUIRED);
    }

    if (tuningProperties.getApprove().isSplitTransaction()) {
      return approveSplit(intentPublicId, idempotencyKeyHeader, customerId, req);
    } else {
      return approveLegacy(intentPublicId, idempotencyKeyHeader, customerId, req);
    }
  }

  // ═══════════════════════════════════════════════════════════════
  //  기존 단일 트랜잭션 경로 (split-transaction=false 폴백)
  // ═══════════════════════════════════════════════════════════════

  /**
   * 레거시 경로: 단일 트랜잭션으로 전체 approve 를 처리한다.
   *
   * <p>approve() 에서 this.approveLegacy() 로 호출하면 self-invocation 이라
   * {@code @Transactional} AOP 가 적용되지 않는다. 따라서
   * {@link TransactionTemplate} 으로 명시적 트랜잭션 경계를 잡는다.
   */
  IdempotentResult<PaymentIntentDetailResponse> approveLegacy(
      UUID intentPublicId, String idempotencyKeyHeader, Long customerId, ApproveRequest req) {

    return transactionTemplate.execute(status -> {

      // 멱등 바디 정규화
      String canonicalBody = canonicalizeApproveBody(req);
      byte[] bodyHash = IdempotencyService.sha256(canonicalBody);

      UUID keyUuid;
      try {
        keyUuid = UUID.fromString(idempotencyKeyHeader);
      } catch (IllegalArgumentException ex) {
        throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
      }
      String path = "/payments/" + intentPublicId + "/approve";
      IdemBegin begin =
          idempotencyService.beginOrLoad(
              IdemActorType.CUSTOMER, customerId, "POST", path, keyUuid, bodyHash);

      IdempotencyKey slot = begin.getRow();

      if (idempotencyService.isBodyConflict(slot, bodyHash)) {
        throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
      }

      if (slot.getStatus() == IdemStatus.DONE) {
        PaymentIntentDetailResponse replay;
        var node = slot.getResponseJson();

        if (node != null && !node.isNull()) {
          replay = parseSnapshot(node);
        } else if (slot.getIntentPublicId() != null) {
          replay = rebuildFromResource(slot.getIntentPublicId());
        } else {
          throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
        }
        return IdempotentResult.okReplay(replay);
      }

      if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
        return IdempotentResult.acceptedWithRetryAfterSeconds(2);
      }

      // 비즈니스 검증
      PaymentIntent intent =
          intentRepository
              .findByPublicId(intentPublicId)
              .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));

      LocalDateTime now = LocalDateTime.now(clock);

      if (intent.getStatus() != PaymentStatus.PENDING) {
        throw new CustomException(ErrorCode.PAYMENT_INTENT_STATUS_CONFLICT);
      }
      if (intent.getExpiresAt() != null && now.isAfter(intent.getExpiresAt())) {
        // [TX expire] — 이 트랜잭션은 곧 롤백되므로 EXPIRED 를 여기서 전이하면 사라진다.
        // REQUIRES_NEW 로 별도 커밋한 뒤 예외를 던진다 (finalizeDeclined 와 동일 규약).
        approveHelper.finalizeExpired(intent.getIntentId(), slot.getId());
        throw new CustomException(ErrorCode.PAYMENT_INTENT_EXPIRED);
      }
      if (!Objects.equals(intent.getCustomerId(), customerId)) {
        throw new CustomException(ErrorCode.PAYMENT_INTENT_OWNER_MISMATCH);
      }

      // PIN 검증 — 토큰 경로 또는 기존 HTTP 경로
      boolean useTokenPathLegacy = tuningProperties.getPin().isTokenEnabled()
          && req.getPinToken() != null && !req.getPinToken().isBlank();

      if (useTokenPathLegacy) {
        // 토큰 로컬 검증 (JPA 리포지토리 호출 0건, Redis jti 기록만)
        try {
          Long tokenCustomerId = pinTokenVerifier.verify(req.getPinToken(), intentPublicId, customerId, intent.getAmount());
          log.debug("[PIN_TOKEN] legacy path verified: tokenCustomerId={}", tokenCustomerId);
        } catch (CustomException e) {
          approveHelper.finalizeDeclined(intent.getIntentId(), slot.getId());
          throw e;
        }
      } else {
        // 기존 PIN 검증 (모놀리식 서버 검사)
        boolean pinOk = customerClient.verifyPin(customerId, req.getPin());
        if (!pinOk) {
          approveHelper.finalizeDeclined(intent.getIntentId(), slot.getId());
          throw new CustomException(ErrorCode.PIN_INVALID);
        }
      }

      // 자금 캡처
      List<PaymentIntentItem> intentItems =
          itemRepository.findByIntent_IntentId(intent.getIntentId());
      FundsService.FundsResult funds = fundsService.capture(intent, intentItems);

      if (funds.isUncertain()) {
        log.warn("자금 캡처 UNCERTAIN - 복구 스케줄러가 처리 예정: intentId={}", intent.getIntentId());
        throw new CustomException(ErrorCode.SERVICE_TIMEOUT);
      }

      if (!funds.isSufficient()) {
        approveHelper.finalizeDeclined(intent.getIntentId(), slot.getId());
        String errorCode = funds.getErrorCode();
        if ("PAYMENT_IN_PROGRESS".equals(errorCode)) {
          throw new CustomException(ErrorCode.PAYMENT_IN_PROGRESS);
        } else if ("FUNDS_CHANGED_BY_OTHER_PAYMENT".equals(errorCode)) {
          throw new CustomException(ErrorCode.FUNDS_CHANGED_BY_OTHER_PAYMENT);
        }
        throw new CustomException(ErrorCode.FUNDS_INSUFFICIENT);
      }
      if (!funds.isPolicyOk()) {
        throw new CustomException(ErrorCode.PAYMENT_POLICY_VIOLATION);
      }

      // 상태 전이
      intent.markApproved(now);

      // 응답 구성
      List<PaymentIntentItemView> itemViews =
          intentItems.stream().map(this::toItemView).collect(Collectors.toList());

      PaymentIntentDetailResponse res = PaymentIntentDetailResponse.from(intent, itemViews);

      // 결제 승인 알림
      if (tuningProperties.getNotification().isAsync()) {
        eventPublisher.publishEvent(new PaymentApprovedEvent(
            intent.getIntentId(), customerId,
            intent.getStoreId(), intent.getAmount()));
      } else {
        sendApprovalNotifications(intent, customerId);
      }

      try {
        idempotencyService.completeStrict(slot, HttpStatus.OK.value(), res, intent.getPublicId());
      } catch (JsonProcessingException e) {
        idempotencyService.completeWithoutSnapshot(slot, HttpStatus.OK.value(), intent.getPublicId());
      }

      return IdempotentResult.ok(res);
    });
  }

  // ═══════════════════════════════════════════════════════════════
  //  트랜잭션 분리 경로 (split-transaction=true, 기본)
  //
  //  [TX-A]  멱등 슬롯 선점 · intent 검증 · items 스냅샷 → 커밋
  //  [NO-TX] PIN 검증 (HTTP) · 자금 캡처 (HTTP) ← 커넥션 미점유
  //  [TX-B]  intent 재조회 · 상태 전이 · 멱등 complete → 커밋
  //  [AFTER] 알림 (세트 #30 이벤트)
  // ═══════════════════════════════════════════════════════════════

  IdempotentResult<PaymentIntentDetailResponse> approveSplit(
      UUID intentPublicId, String idempotencyKeyHeader, Long customerId, ApproveRequest req) {

    long t0 = System.currentTimeMillis();

    // 바디 해시 (트랜잭션 불필요)
    String canonicalBody = canonicalizeApproveBody(req);
    byte[] bodyHash = IdempotencyService.sha256(canonicalBody);

    // ───────────────────────────────────────────────
    // [TX-A] 멱등 슬롯 + intent 검증 + items 스냅샷
    // ───────────────────────────────────────────────
    ApprovePhaseAResult phaseA = approveHelper.prepareApproval(
        intentPublicId, idempotencyKeyHeader, customerId, bodyHash);

    if (phaseA.hasEarlyReturn()) {
      return phaseA.getEarlyReturn();
    }

    // ───────────────────────────────────────────────
    // [TX-B expire] intent 만료 — EXPIRED 를 DB 에 영속 (커밋 보장) 후 예외
    //
    // TX-A 안에서 전이 + 예외를 하면 TX-A 롤백으로 전이가 사라진다.
    // ───────────────────────────────────────────────
    if (phaseA.isExpired()) {
      log.warn("[APPROVE_PHASE] phase=EXPIRED intentId={} expiresAt={}",
          phaseA.getIntentId(), phaseA.getExpiresAt());
      approveHelper.finalizeExpired(phaseA.getIntentId(), phaseA.getIdemSlotId());
      throw new CustomException(ErrorCode.PAYMENT_INTENT_EXPIRED);
    }

    // ───────────────────────────────────────────────
    // [NO-TX] PIN 검증 — DB 커넥션 미점유
    //
    // 토큰 경로: 로컬 JWT 검증 + Redis jti 기록 (monolith 호출 0)
    // PIN 경로:  기존 HTTP 검증 (monolith 왕복 1회)
    // ───────────────────────────────────────────────
    long pinT0 = System.currentTimeMillis();
    boolean useTokenPath = tuningProperties.getPin().isTokenEnabled()
        && req.getPinToken() != null && !req.getPinToken().isBlank();

    if (useTokenPath) {
      // ── 토큰 로컬 검증 (JPA 리포지토리 호출 0건) ──
      try {
        Long tokenCustomerId = pinTokenVerifier.verify(req.getPinToken(), intentPublicId, customerId, phaseA.getAmount());
        log.debug("[PIN_TOKEN] split path verified: tokenCustomerId={}", tokenCustomerId);
      } catch (CustomException e) {
        log.warn("[APPROVE_PHASE] phase=PIN_TOKEN_FAIL intentId={} error={}",
            phaseA.getIntentId(), e.getErrorCode());
        approveHelper.finalizeDeclined(phaseA.getIntentId(), phaseA.getIdemSlotId());
        throw e;
      }
      long pinElapsed = System.currentTimeMillis() - pinT0;
      log.info("[APPROVE_PHASE] phase=PIN_TOKEN intentId={} elapsedMs={}",
          phaseA.getIntentId(), pinElapsed);
    } else {
      // ── 기존 PIN HTTP 검증 ──
      boolean pinOk;
      try {
        pinOk = customerClient.verifyPin(customerId, req.getPin());
      } catch (Exception e) {
        // PIN 서비스 불가 — 멱등 슬롯은 IN_PROGRESS 상태로 남고
        // cleanupStalledInProgress(5분)가 정리한다.
        log.warn("[APPROVE_ORPHAN] intentId={} reason=PIN_SERVICE_ERROR error={}",
            phaseA.getIntentId(), e.getMessage());
        throw e;
      }
      long pinElapsed = System.currentTimeMillis() - pinT0;
      log.info("[APPROVE_PHASE] phase=PIN intentId={} elapsedMs={} result={}",
          phaseA.getIntentId(), pinElapsed, pinOk);

      if (!pinOk) {
        // [TX-B decline] — DECLINED 를 DB 에 영속 (커밋 보장)
        approveHelper.finalizeDeclined(phaseA.getIntentId(), phaseA.getIdemSlotId());
        throw new CustomException(ErrorCode.PIN_INVALID);
      }
    }

    // ───────────────────────────────────────────────
    // [NO-TX] 자금 캡처 (HTTP) — DB 커넥션 미점유
    //
    // FundsService.capture() 는 intent 엔티티를 인자로 받지만
    // 실제로는 publicId/walletId/storeId/amount 만 읽는다.
    // UNCERTAIN 경로에서 IntentStatusUpdater.markUncertain() 이
    // REQUIRES_NEW 로 자체 커넥션을 열어 처리한다.
    // ───────────────────────────────────────────────
    long fundsT0 = System.currentTimeMillis();

    // FundsService.capture 에 전달할 경량 intent 프록시 생성
    // (detached 엔티티 대신 필요 필드만 세팅한 새 인스턴스)
    PaymentIntent intentSnapshot = PaymentIntent.builder()
        .intentId(phaseA.getIntentId())
        .publicId(phaseA.getIntentPublicId())
        .customerId(phaseA.getCustomerId())
        .walletId(phaseA.getWalletId())
        .storeId(phaseA.getStoreId())
        .amount(phaseA.getAmount())
        .status(PaymentStatus.PENDING)
        .build();

    // items 는 FundsService.capture 가 ItemSnapshot 으로 변환하는데
    // PaymentIntentItem 필드만 필요하므로 경량 객체 생성
    List<PaymentIntentItem> itemSnapshots = phaseA.getItemViews().stream()
        .map(iv -> PaymentIntentItem.builder()
            .menuId(iv.getMenuId())
            .menuNameSnap(iv.getName())
            .unitPriceSnap(iv.getUnitPrice())
            .quantity(iv.getQuantity())
            .build())
        .collect(Collectors.toList());

    FundsService.FundsResult funds = fundsService.capture(intentSnapshot, itemSnapshots);
    long fundsElapsed = System.currentTimeMillis() - fundsT0;
    log.info("[APPROVE_PHASE] phase=FUNDS intentId={} elapsedMs={} uncertain={} sufficient={}",
        phaseA.getIntentId(), fundsElapsed, funds.isUncertain(), funds.isSufficient());

    if (funds.isUncertain()) {
      // IntentStatusUpdater 가 이미 UNCERTAIN 으로 마킹했다.
      // 복구 스케줄러가 처리 예정.
      log.warn("[APPROVE_ORPHAN] intentId={} reason=FUNDS_UNCERTAIN",
          phaseA.getIntentId());
      throw new CustomException(ErrorCode.SERVICE_TIMEOUT);
    }

    if (!funds.isSufficient()) {
      // [TX-B decline]
      approveHelper.finalizeDeclined(phaseA.getIntentId(), phaseA.getIdemSlotId());
      String errorCode = funds.getErrorCode();
      if ("PAYMENT_IN_PROGRESS".equals(errorCode)) {
        throw new CustomException(ErrorCode.PAYMENT_IN_PROGRESS);
      } else if ("FUNDS_CHANGED_BY_OTHER_PAYMENT".equals(errorCode)) {
        throw new CustomException(ErrorCode.FUNDS_CHANGED_BY_OTHER_PAYMENT);
      }
      throw new CustomException(ErrorCode.FUNDS_INSUFFICIENT);
    }
    if (!funds.isPolicyOk()) {
      approveHelper.finalizeDeclined(phaseA.getIntentId(), phaseA.getIdemSlotId());
      throw new CustomException(ErrorCode.PAYMENT_POLICY_VIOLATION);
    }

    // ───────────────────────────────────────────────
    // [TX-B] intent 재조회 + APPROVED + 멱등 완료
    // ───────────────────────────────────────────────
    PaymentIntentDetailResponse res;
    try {
      res = approveHelper.finalizeApproved(
          phaseA.getIntentId(), phaseA.getIdemSlotId(), phaseA.getItemViews());
    } catch (ObjectOptimisticLockingFailureException e) {
      // @Version 충돌 — 재시도하지 않는다. 멱등 경로로 흡수.
      log.warn("[APPROVE_ORPHAN] intentId={} reason=OPTIMISTIC_LOCK_CONFLICT",
          phaseA.getIntentId());
      throw new CustomException(ErrorCode.PAYMENT_INTENT_STATUS_CONFLICT);
    }

    long totalElapsed = System.currentTimeMillis() - t0;
    log.info("[APPROVE_PHASE] phase=COMPLETE intentId={} elapsedMs={}", phaseA.getIntentId(), totalElapsed);

    // ───────────────────────────────────────────────
    // [AFTER] 알림 (세트 #30 이벤트 — TX 밖)
    // ───────────────────────────────────────────────
    if (tuningProperties.getNotification().isAsync()) {
      eventPublisher.publishEvent(new PaymentApprovedEvent(
          phaseA.getIntentId(), customerId,
          phaseA.getStoreId(), phaseA.getAmount()));
    } else {
      // 동기 폴백: 경량 intent 로 알림 전송
      sendApprovalNotifications(intentSnapshot, customerId);
    }

    return IdempotentResult.ok(res);
  }

  private void sendApprovalNotifications(PaymentIntent intent, Long customerId) {
    try {
      StoreResponse store = storeClient.getStore(intent.getStoreId()).orElse(null);
      String storeName = store != null ? store.getStoreName() : "매장";
      Long ownerId = store != null ? store.getOwnerId() : null;

      // 점주 알림 - 동기 전송
      if (ownerId != null) {
        String ownerContent = String.format("%,d원 결제가 완료되었습니다.", intent.getAmount());
        notificationClient.sendToOwner(ownerId, "PAYMENT_APPROVED", ownerContent);
      }

      // 고객 알림 - 동기 전송
      String customerContent =
          String.format("%s에서 %,d원 결제가 완료되었습니다.", storeName, intent.getAmount());
      notificationClient.sendToCustomer(customerId, "PAYMENT_APPROVED", customerContent);

      log.info("결제 승인 알림 전송 완료 - 손님ID: {}, 결제 금액: {}", customerId, intent.getAmount());
    } catch (Exception e) {
      log.warn("결제 승인 알림 전송 실패 - 손님ID: {}, error: {}", customerId, e.getMessage());
      // 알림 실패해도 결제 승인은 성공 처리
    }
  }

  /** 결제 의도 취소 */
  @Transactional
  public void cancel(UUID publicId, Long customerId) {
    PaymentIntent intent =
        intentRepository
            .findByPublicId(publicId)
            .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));

    if (!intent.getCustomerId().equals(customerId)) {
      throw new CustomException(ErrorCode.FORBIDDEN);
    }

    intent.markCanceled(LocalDateTime.now(clock));
    intentRepository.save(intent);
  }

  /* ---------- 내부 유틸 ---------- */

  private PaymentIntentItemView toItemView(PaymentIntentItem it) {
    long line =
        (it.getLineTotal() != null) ? it.getLineTotal() : it.getUnitPriceSnap() * it.getQuantity();
    return PaymentIntentItemView.builder()
        .menuId(it.getMenuId())
        .name(it.getMenuNameSnap())
        .unitPrice(it.getUnitPriceSnap())
        .quantity(it.getQuantity())
        .lineTotal(line)
        .build();
  }

  private String canonicalizeInitiateBody(PaymentInitiateRequest req) {
    List<CanonicalInitiate.Item> normItems = new ArrayList<>();
    for (PaymentInitiateItemDto it : req.getOrderItems()) {
      if (it.getQuantity() <= 0) {
        throw new CustomException(ErrorCode.PAYMENT_INIT_QUANTITY_INVALID);
      }
      CanonicalInitiate.Item ci =
          CanonicalInitiate.Item.builder()
              .menuId(it.getMenuId())
              .quantity(it.getQuantity())
              .build();
      normItems.add(ci);
    }
    normItems.sort(
        (a, b) -> {
          int c = a.getMenuId().compareTo(b.getMenuId());
          if (c != 0) return c;
          return Integer.compare(a.getQuantity(), b.getQuantity());
        });

    CanonicalInitiate canonical =
        CanonicalInitiate.builder().storeId(req.getStoreId()).items(normItems).build();

    try {
      return canonicalObjectMapper.writeValueAsString(canonical);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new CustomException(ErrorCode.REQUEST_CANONICALIZE_FAILED);
    }
  }

  private String canonicalizeApproveBody(ApproveRequest req) {
    boolean hasToken = req.getPinToken() != null && !req.getPinToken().isBlank();

    CanonicalApprove canonical;
    if (hasToken) {
      // 토큰 경로: 토큰 문자열 자체를 정규화 대상으로 포함
      canonical = CanonicalApprove.builder().pinToken(req.getPinToken()).build();
    } else {
      // PIN 경로: 기존 로직
      String raw = req.getPin();
      String normalized = (raw == null) ? null : raw.replaceAll("\\s+", "");

      if (normalized == null || !normalized.matches("\\d{6}")) {
        throw new CustomException(ErrorCode.PIN_INVALID);
      }
      canonical = CanonicalApprove.builder().pin(normalized).build();
    }

    try {
      return canonicalObjectMapper.writeValueAsString(canonical);
    } catch (JsonProcessingException e) {
      throw new CustomException(ErrorCode.REQUEST_CANONICALIZE_FAILED);
    }
  }

  private PaymentIntentDetailResponse parseSnapshot(JsonNode node) {
    try {
      return canonicalObjectMapper.treeToValue(node, PaymentIntentDetailResponse.class);
    } catch (Exception e) {
      throw new CustomException(ErrorCode.RESPONSE_SNAPSHOT_PARSE_FAILED);
    }
  }

  private PaymentIntentDetailResponse rebuildFromResource(UUID intentPublicId) {
    return getDetail(intentPublicId);
  }
}
