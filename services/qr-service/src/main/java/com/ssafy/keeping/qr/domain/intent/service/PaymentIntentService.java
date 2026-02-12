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
import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import com.ssafy.keeping.qr.domain.qr.service.QrTokenService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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
            Clock clock
    ) {
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
    }

    /**
     * 결제 의도 생성
     */
    @Transactional
    public IdempotentResult<PaymentIntentDetailResponse> initiate(String qrTokenId,
                                                                  String idempotencyKeyHeader,
                                                                  Long ownerId,
                                                                  PaymentInitiateRequest req) {

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
        String path = "/cpqr/" + qrTokenId + "/initiate";
        IdemBegin begin = idempotencyService.beginOrLoad(IdemActorType.MERCHANT, ownerId, "POST", path, keyUuid, bodyHash);

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

        // QR 검증 - 자체 Redis에서 조회
        QrToken qr = qrTokenService.findToken(qrTokenId)
                .orElseThrow(() -> new CustomException(ErrorCode.QR_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);

        if (qr.getExpiresAt() != null && now.isAfter(qr.getExpiresAt())) {
            throw new CustomException(ErrorCode.QR_EXPIRED);
        }
        if (!Objects.equals(qr.getBindStoreId(), req.getStoreId())) {
            throw new CustomException(ErrorCode.QR_STORE_MISMATCH);
        }

        // 메뉴 로딩/검증
        Set<Long> uniqueMenuIds = req.getOrderItems().stream()
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

        Map<Long, MenuResponse> menuById = menus.stream()
                .collect(Collectors.toMap(MenuResponse::getMenuId, m -> m));

        // 합계 계산 (quantity 검증은 canonicalizeInitiateBody에서 완료됨)
        long total = 0L;
        for (PaymentInitiateItemDto item : req.getOrderItems()) {
            MenuResponse m = menuById.get(item.getMenuId());
            total += (long) m.getPrice() * item.getQuantity();
        }

        // Intent 생성
        PaymentIntent intent = PaymentIntent.builder()
                .publicId(IdUtil.newUuidV7())
                .qrTokenId(qrTokenId)
                .customerId(qr.getCustomerId())
                .walletId(qr.getWalletId())
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
            PaymentIntentItem row = PaymentIntentItem.builder()
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
        List<PaymentIntentItemView> itemViews = items.stream()
                .map(this::toItemView)
                .collect(Collectors.toList());

        PaymentIntentDetailResponse res = PaymentIntentDetailResponse.from(intent, itemViews);

        // 결제 요청 알림 - 동기 전송 (고객이 즉시 알림을 받아야 승인 가능)
        try {
            StoreResponse store = storeClient.getStore(intent.getStoreId())
                    .orElse(null);
            String storeName = store != null ? store.getStoreName() : "매장";

            String notificationContent = String.format("%s에서 %,d원 결제 요청이 도착했습니다.", storeName, intent.getAmount());
            notificationClient.sendToCustomer(
                    qr.getCustomerId(),
                    "PAYMENT_REQUEST",
                    notificationContent
            );
            log.info("결제 요청 알림 전송 완료 - 손님ID: {}, 결제 금액: {}", qr.getCustomerId(), intent.getAmount());
        } catch (Exception e) {
            log.warn("결제 요청 알림 전송 실패 - 손님ID: {}, error: {}", qr.getCustomerId(), e.getMessage());
            // 알림 실패해도 결제 시작은 성공 처리 (고객이 앱에서 직접 확인 가능)
        }

        // 멱등 완료 기록
        idempotencyService.complete(slot, HttpStatus.CREATED.value(), res, intent.getPublicId());

        return IdempotentResult.created(res);
    }

    @Transactional(readOnly = true)
    public PaymentIntentDetailResponse getDetail(UUID intentPublicId) {
        PaymentIntent intent = intentRepository.findByPublicId(intentPublicId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));
        List<PaymentIntentItem> rows = itemRepository.findByIntent_IntentId(intent.getIntentId());
        List<PaymentIntentItemView> itemViews = rows.stream()
                .map(this::toItemView)
                .collect(Collectors.toList());
        return PaymentIntentDetailResponse.from(intent, itemViews);
    }

    /**
     * 결제 승인
     */
    @Transactional
    public IdempotentResult<PaymentIntentDetailResponse> approve(UUID intentPublicId,
                                                                 String idempotencyKeyHeader,
                                                                 Long customerId,
                                                                 ApproveRequest req) {
        // ═══════════════════════════════════════════════════
        // 입력 검증
        // ═══════════════════════════════════════════════════
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }
        if (req == null || req.getPin() == null || req.getPin().isBlank()) {
            throw new CustomException(ErrorCode.PIN_REQUIRED);
        }

        // ═══════════════════════════════════════════════════
        // 멱등성 게이트
        // ═══════════════════════════════════════════════════
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
        IdemBegin begin = idempotencyService.beginOrLoad(IdemActorType.CUSTOMER, customerId, "POST", path, keyUuid, bodyHash);

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
        PaymentIntent intent = intentRepository.findByPublicId(intentPublicId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);

        if (intent.getStatus() != PaymentStatus.PENDING) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_STATUS_CONFLICT);
        }
        if (intent.getExpiresAt() != null && now.isAfter(intent.getExpiresAt())) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_EXPIRED);
        }
        if (!Objects.equals(intent.getCustomerId(), customerId)) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_OWNER_MISMATCH);
        }

        // PIN 검증 (모놀리식 서버 검사)
        boolean pinOk = customerClient.verifyPin(customerId, req.getPin());
        if (!pinOk) {
            throw new CustomException(ErrorCode.PIN_INVALID);
        }

        // 자금 캡처
        List<PaymentIntentItem> intentItems = itemRepository.findByIntent_IntentId(intent.getIntentId());
        FundsService.FundsResult funds = fundsService.capture(intent, intentItems);

        if (!funds.isSufficient()) {
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
        List<PaymentIntentItemView> itemViews = intentItems.stream()
                .map(this::toItemView)
                .collect(Collectors.toList());

        PaymentIntentDetailResponse res = PaymentIntentDetailResponse.from(intent, itemViews);

        // 결제 승인 알림 - 동기 전송 (점주가 즉시 알림을 받아야 고객 퇴장 가능)
        sendApprovalNotifications(intent, customerId);

        try {
            idempotencyService.completeStrict(slot, HttpStatus.OK.value(), res, intent.getPublicId());
        } catch (JsonProcessingException e) {
            idempotencyService.completeWithoutSnapshot(slot, HttpStatus.OK.value(), intent.getPublicId());
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
            String customerContent = String.format("%s에서 %,d원 결제가 완료되었습니다.", storeName, intent.getAmount());
            notificationClient.sendToCustomer(customerId, "PAYMENT_APPROVED", customerContent);

            log.info("결제 승인 알림 전송 완료 - 손님ID: {}, 결제 금액: {}", customerId, intent.getAmount());
        } catch (Exception e) {
            log.warn("결제 승인 알림 전송 실패 - 손님ID: {}, error: {}", customerId, e.getMessage());
            // 알림 실패해도 결제 승인은 성공 처리
        }
    }

    /* ---------- 내부 유틸 ---------- */

    private PaymentIntentItemView toItemView(PaymentIntentItem it) {
        long line = (it.getLineTotal() != null) ? it.getLineTotal() : it.getUnitPriceSnap() * it.getQuantity();
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
            CanonicalInitiate.Item ci = CanonicalInitiate.Item.builder()
                    .menuId(it.getMenuId())
                    .quantity(it.getQuantity())
                    .build();
            normItems.add(ci);
        }
        normItems.sort((a, b) -> {
            int c = a.getMenuId().compareTo(b.getMenuId());
            if (c != 0) return c;
            return Integer.compare(a.getQuantity(), b.getQuantity());
        });

        CanonicalInitiate canonical = CanonicalInitiate.builder()
                .storeId(req.getStoreId())
                .items(normItems)
                .build();

        try {
            return canonicalObjectMapper.writeValueAsString(canonical);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new CustomException(ErrorCode.REQUEST_CANONICALIZE_FAILED);
        }
    }

    private String canonicalizeApproveBody(ApproveRequest req) {
        String raw = req.getPin();
        String normalized = (raw == null) ? null : raw.replaceAll("\\s+", "");

        if (normalized == null || !normalized.matches("\\d{6}")) {
            throw new CustomException(ErrorCode.PIN_INVALID);
        }

        CanonicalApprove canonical = CanonicalApprove.builder()
                .pin(normalized)
                .build();

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
