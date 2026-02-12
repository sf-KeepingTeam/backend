package com.ssafy.keeping.domain.payment.intent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.pin.service.PinAuthService;
import com.ssafy.keeping.domain.group.model.GroupMember;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.idempotency.repository.IdempotencyKeyRepository;
import com.ssafy.keeping.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.payment.common.IdUtil;
import com.ssafy.keeping.domain.payment.funds.dto.FundsResult;
import com.ssafy.keeping.domain.payment.funds.service.FundsService;
import com.ssafy.keeping.domain.payment.intent.canonical.CanonicalApprove;
import com.ssafy.keeping.domain.payment.intent.canonical.CanonicalInitiate;
import com.ssafy.keeping.domain.payment.intent.constant.PaymentStatus;
import com.ssafy.keeping.domain.payment.intent.dto.*;
import com.ssafy.keeping.domain.payment.intent.model.PaymentIntent;
import com.ssafy.keeping.domain.payment.intent.model.PaymentIntentItem;
import com.ssafy.keeping.domain.payment.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.domain.payment.intent.repository.PaymentIntentRepository;
import com.ssafy.keeping.domain.payment.qr.constant.QrState;
import com.ssafy.keeping.domain.payment.qr.model.QrToken;
import com.ssafy.keeping.domain.payment.qr.repository.QrTokenRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentIntentService {

    private final PaymentIntentRepository intentRepository;
    private final PaymentIntentItemRepository itemRepository;
    private final QrTokenRepository qrTokenRepository;
    private final MenuRepository menuRepository;
    private final PinAuthService pinAuthService;
    private final FundsService fundsService;
    private final NotificationService notificationService;
    private final StoreRepository storeRepository;
    private final WalletRepository walletRepository;
    private final GroupMemberRepository groupMemberRepository;

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final IdempotencyService idempotencyService;
    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 결제 의도 생성
     * - 멱등 스코프: (actorType=MERCHANT, actorId=merchantUserId, path=/cpqr/{qrTokenId}/initiate, key=Idempotency-Key)
     * - 상태 흐름:
     *   DONE                           → 저장된 응답 재생(200 OK)
     *   IN_PROGRESS(타 프로세스 선점)     → 202 Accepted
     *   신규                            → 본 처리 수행 → DONE 기록 후 201 Created
     */
    @Transactional
    public IdempotentResult<PaymentIntentDetailResponse> initiate(UUID qrTokenId,
                                                                  String idempotencyKeyHeader,
                                                                  Long ownerId,
                                                                  PaymentInitiateRequest req) {

        if (req == null || req.getOrderItems() == null || req.getOrderItems().isEmpty()) {
            throw new CustomException(ErrorCode.PAYMENT_INIT_ORDER_EMPTY); // 주문 항목이 비어있습니다.
        }
        if (req.getStoreId() == null) {
            throw new CustomException(ErrorCode.PAYMENT_INIT_STORE_ID_REQUIRED); // storeId는 필수입니다.
        }
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED); // Idempotency-Key 헤더가 필요합니다.
        }

        // 멱등 바디 정규화 → SHA-256
        String canonicalBody = canonicalizeInitiateBody(req); // 정규화
        byte[] bodyHash = IdempotencyService.sha256(canonicalBody); // SHA-256

        // 멱등 선점 또는 로드
        UUID keyUuid = UUID.fromString(idempotencyKeyHeader);
        String path = "/cpqr/" + qrTokenId + "/initiate"; // 스코프 정규화
        IdemBegin begin = idempotencyService.beginOrLoad(IdemActorType.MERCHANT, ownerId, "POST", path, keyUuid, bodyHash);

        IdempotencyKey slot = begin.getRow();

        // 본문 충돌 확인
        if (idempotencyService.isBodyConflict(slot, bodyHash)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT); // Idempotency-Key 충돌: 요청 내용이 처음과 다릅니다.
        }

        if (slot.getStatus() == IdemStatus.DONE) {
            // 스냅샷이 있으면 그대로, 없으면 리소스 재조회해서 응답 구성
            PaymentIntentDetailResponse replay;

            try {
                var node = slot.getResponseJson(); // JsonNode
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

        // 다른 처리에서 IN_PROGRESS로 선점
        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return IdempotentResult.acceptedWithRetryAfterSeconds(2);
        }

        // QR 검증 - QrState가 ISSUED(발급됨)이어야 한다.
        QrToken qr = qrTokenRepository.findByQrTokenIdAndState(qrTokenId, QrState.ISSUED)
                .orElseThrow(() -> new CustomException(ErrorCode.QR_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);

        if (qr.getExpiresAt() != null && now.isAfter(qr.getExpiresAt())) {
            throw new CustomException(ErrorCode.QR_EXPIRED); // QR 토큰이 만료되었습니다.
        }
        if (!Objects.equals(qr.getBindStoreId(), req.getStoreId())) {
            throw new CustomException(ErrorCode.QR_STORE_MISMATCH); // 바인딩된 매장과 일치하지 않는 요청입니다.
        }

        // TODO: ownerId 소속 매장 검증 로직







        // 다른 도메인(애그리거트) 코드임 수정 필요...!!!

        // 메뉴 로딩/검증
        Set<Long> uniqueMenuIds = req.getOrderItems().stream().map(orderItem -> orderItem.getMenuId()).collect(Collectors.toSet());

        List<Long> menuIdList = new ArrayList<>(uniqueMenuIds);

        List<Menu> menus = menuRepository.findAllById(menuIdList);
        if (menus.size() != uniqueMenuIds.size()) {
            throw new CustomException(ErrorCode.MENU_NOT_FOUND); // 해당 메뉴를 찾을 수 없습니다.
        }

        for (Menu m : menus) {
            Long menuStoreId = m.getStore().getStoreId();
            if (!Objects.equals(menuStoreId, req.getStoreId())) {
                throw new CustomException(ErrorCode.MENU_CROSS_STORE_CONFLICT); // 다른 매장의 메뉴가 포함되어 있습니다.
            }
            if (!m.isActive() || m.isSoldOut()) {
                throw new CustomException(ErrorCode.MENU_UNAVAILABLE); // 품절/비활성 메뉴가 포함되어 있습니다.
            }
        }

        Map<Long, Menu> menuById = new HashMap<>();
        for (Menu m : menus) {
            menuById.put(m.getMenuId(), m);
        }

        // 합계 계산
        long total = 0L;
        for (PaymentInitiateItemDto item : req.getOrderItems()) {
            Menu m = menuById.get(item.getMenuId());
            if (item.getQuantity() <= 0) throw new CustomException(ErrorCode.PAYMENT_INIT_QUANTITY_INVALID); // 수량은 1 이상이어야 합니다.
            total += (long) m.getPrice() * item.getQuantity();
        }

        // Intent 생성
        PaymentIntent intent = PaymentIntent.builder()
                .publicId(IdUtil.newUuidV7())
                .qrToken(qr)
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
            Menu m = menuById.get(item.getMenuId());
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
        List<PaymentIntentItemView> itemViews = items.stream().map(item -> toItemView(item)).collect(Collectors.toList());

        PaymentIntentDetailResponse res = PaymentIntentDetailResponse.from(intent, itemViews);

        try {
            Long customerId = qr.getCustomerId();
            Store store = storeRepository.findById(intent.getStoreId()).orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));
            String storeName = store.getStoreName();

            String notificationContent = String.format("%s에서 결제 요청이 도착하였습니다.", storeName);

            notificationService.sendToCustomer(
                    customerId,
                    NotificationType.PAYMENT_REQUEST,
                    notificationContent
            );

            log.info("결제 요청 알림 전송 완료 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, intent.getAmount(), intent.getStoreId());
        } catch (Exception e) {
            log.info("결제 요청 알림 전송 완료 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", qr.getCustomerId(), intent.getAmount(), intent.getStoreId());
            // 알림 실패는 비즈니스 로직에 영향을 주지 않음
        }

        // 멱등 완료 기록(DONE + 응답 스냅샷)
        idempotencyService.complete(slot, HttpStatus.CREATED.value(), res, intent.getPublicId());

        return IdempotentResult.created(res);
    }

    @Transactional(readOnly = true)
    public PaymentIntentDetailResponse getDetail(UUID intentPublicId) {
        PaymentIntent intent = intentRepository.findByPublicId(intentPublicId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));
        List<PaymentIntentItem> rows = itemRepository.findByIntent_IntentId(intent.getIntentId());
        List<PaymentIntentItemView> itemViews = new ArrayList<>();
        for (PaymentIntentItem it : rows) {
            itemViews.add(toItemView(it)); // 또는 this.toItemView(it)
        }
        return PaymentIntentDetailResponse.from(intent, itemViews);
    }

    /**
     * 결제 승인
     * - 멱등 스코프: (actorType=CUSTOMER, actorId=customerId, path=/payments/{intentId}/approve, key=Idempotency-Key)
     * - 상태 흐름:
     *   DONE                           → 저장된 응답 재생(200 OK)
     *   IN_PROGRESS(타 프로세스 선점)     → 202 Accepted
     *   신규                            → 본 처리 수행 → DONE 기록 후 200 OK
     */
    @Transactional
    public IdempotentResult<PaymentIntentDetailResponse> approve(UUID intentPublicId,
                                                                 String idempotencyKeyHeader,
                                                                 Long customerId,
                                                                 ApproveRequest req) {
        // 입력 검증 (헤더/바디)
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED); // Idempotency-Key 헤더가 필요합니다.
        }
        if (req == null || req.getPin() == null || req.getPin().isBlank()) {
            throw new CustomException(ErrorCode.PIN_REQUIRED); // 결제 비밀번호(PIN)는 필수입니다.
        }

        // 멱등 바디 정규화 → SHA-256
        String canonicalBody = canonicalizeApproveBody(req); // 키 정렬/NULL 제거
        byte[] bodyHash = IdempotencyService.sha256(canonicalBody); // SHA-256

        // 멱등 선점 또는 로드
        UUID keyUuid;
        try {
            keyUuid = UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }
        String path = "/payments/" + intentPublicId + "/approve"; // 스코프 정규화
        IdemBegin begin = idempotencyService.beginOrLoad(IdemActorType.CUSTOMER, customerId, "POST", path, keyUuid, bodyHash);

        IdempotencyKey slot = begin.getRow();

        // 본문 충돌 확인
        if (idempotencyService.isBodyConflict(slot, bodyHash)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT); // Idempotency-Key 충돌: 요청 내용이 처음과 다릅니다.
        }

        // DONE 재생
        if (slot.getStatus() == IdemStatus.DONE) {
            PaymentIntentDetailResponse replay;
            var node = slot.getResponseJson();

            if (node != null && !node.isNull()) {
                replay = parseSnapshot(node); // ← JsonNode 버전 사용
            } else if (slot.getIntentPublicId() != null) {
                replay = rebuildFromResource(slot.getIntentPublicId());
            } else {
                throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
            }
            return IdempotentResult.okReplay(replay);
        }

        // 타 프로세스가 IN_PROGRESS 선점 중이면 202
        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return IdempotentResult.acceptedWithRetryAfterSeconds(2); // 202
        }

        // 비즈니스 검증/처리 시작
        PaymentIntent intent = intentRepository.findByPublicId(intentPublicId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND)); // 결제 요청 찾을 수 없습니다.

        LocalDateTime now = LocalDateTime.now(clock);

        if (intent.getStatus() != PaymentStatus.PENDING) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_STATUS_CONFLICT); // 결제 요청 상태가 승인 가능 상태가 아닙니다.
        }
        if (intent.getExpiresAt() != null && now.isAfter(intent.getExpiresAt())) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_EXPIRED); // 결제 요청의 승인 가능 시간이 만료되었습니다.
        }
        if (!Objects.equals(intent.getCustomerId(), customerId)) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_OWNER_MISMATCH); // 결제 요청 소유자와 승인 주체가 일치하지 않습니다.
        }

        // PIN 검증
        boolean pinOk = pinAuthService.verify(customerId, req.getPin());
        if (!pinOk) {
            throw new CustomException(ErrorCode.PIN_INVALID); // 결제 비밀번호(PIN)가 올바르지 않습니다.
        }

        // --- 잔액/한도 검증 및 차감(원자 UPDATE 또는 내부 비관락; Intent는 낙관락 유지) ---
        FundsResult funds = fundsService.capture(intent);
        if (!funds.isSufficient()) {
            throw new CustomException(ErrorCode.FUNDS_INSUFFICIENT); // 잔액이 부족합니다.
        }
        if (!funds.isPolicyOk()) {
            throw new CustomException(ErrorCode.PAYMENT_POLICY_VIOLATION); // 결제 정책에 따라 승인할 수 없습니다.
        }

        // --- 상태 전이 (낙관적 락: 커밋 시점에 version 비교) ---
        intent.markApproved(now); // status=SUCCEEDED, succeededAt=now


        // 품목 스냅샷 로딩 및 뷰 변환
        List<PaymentIntentItem> intentItems =
                itemRepository.findByIntent_IntentId(intent.getIntentId());

        List<PaymentIntentItemView> itemViews = new ArrayList<>();
        for (PaymentIntentItem it : intentItems) {
            itemViews.add(toItemView(it));
        }

        PaymentIntentDetailResponse res = PaymentIntentDetailResponse.from(intent, itemViews);

        Store store = storeRepository.findById(intent.getStoreId()).orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));
        Wallet wallet = walletRepository.findById(intent.getWalletId()).orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        // 알림 전송
        if (wallet.getWalletType() == WalletType.INDIVIDUAL) { // 개인 지갑
            // 결제 완료된 가게에 알림 전송
            try {
                Long ownerId = store.getOwner().getOwnerId();
                String customerName = wallet.getCustomer().getName();
                String notificationContent = String.format("%s님이 %,d포인트를 결제 승인하였습니다.",
                        customerName, intent.getAmount());

                notificationService.sendToOwner(
                        ownerId,
                        NotificationType.PERSONAL_POINT_USE,
                        notificationContent
                );

                log.info("점주 알림 전송 완료 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, intent.getAmount(), intent.getStoreId());
            } catch (Exception e) {
                Long ownerId = store.getOwner().getOwnerId();
                log.warn("점주 알림 전송 실패 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, intent.getAmount(), intent.getStoreId());
            }
            // 결제한 손님에게 알림 전송
            try {
                String notificationContent = String.format("%s에서 %,d포인트 사용이 완료되었습니다.",
                        store.getStoreName(), intent.getAmount());

                notificationService.sendToOwner(
                        customerId,
                        NotificationType.POINT_CHARGE,
                        notificationContent
                );

                log.info("결제 수락 알림 전송 완료 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, intent.getAmount(), intent.getStoreId());
            } catch (Exception e) {
                log.warn("결제 수락 알림 전송 실패 - 손님ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, intent.getAmount(), intent.getStoreId());
            }
        } else { // 모임 지갑
            // 결제 완료된 가게에 알림 전송
            String groupName = wallet.getGroup().getGroupName();
            try {
                Long ownerId = store.getOwner().getOwnerId();
                String notificationContent = String.format("%s모임에서 %,d포인트를 결제 승인하였습니다.",
                        groupName, intent.getAmount());

                notificationService.sendToOwner(
                        ownerId,
                        NotificationType.GROUP_POINT_USE,
                        notificationContent
                );

                log.info("점주 알림 전송 완료 - 손님ID: {}, 그룹ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, wallet.getGroup().getGroupId(), intent.getAmount(), intent.getStoreId());
            } catch (Exception e) {
                log.warn("점주 알림 전송 완료 - 손님ID: {}, 그룹ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, wallet.getGroup().getGroupId(), intent.getAmount(), intent.getStoreId());
            }

            Long groupId = wallet.getGroup().getGroupId();
            List<GroupMember> groupMembers = groupMemberRepository.findAllByGroup_GroupId(groupId);

            for (GroupMember groupMember : groupMembers) {
                try {
                    String notificationContent = String.format("%s에서 %s지갑의 %,d포인트가 사용되었습니다.",
                            store.getStoreName(), groupName, intent.getAmount());

                    notificationService.sendToOwner(
                            groupMember.getGroupMemberId(),
                            NotificationType.GROUP_POINT_USE,
                            notificationContent
                    );

                    log.info("결제 수락 알림 전송 완료 - 손님ID: {}, 그룹ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, wallet.getGroup().getGroupId(), intent.getAmount(), intent.getStoreId());
                } catch (Exception e) {
                    log.warn("결제 수락 알림 전송 실패 - 손님ID: {}, 그룹ID: {}, 결제 금액: {}, 사용 가게 ID: {}", customerId, wallet.getGroup().getGroupId(), intent.getAmount(), intent.getStoreId());
                }
            }
        }

        try {
            idempotencyService.completeStrict(slot, HttpStatus.OK.value(), res, intent.getPublicId());
        } catch (JsonProcessingException e) {
            idempotencyService.completeWithoutSnapshot(slot, HttpStatus.OK.value(), intent.getPublicId());
        }

        return IdempotentResult.ok(res);
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

    /** initiate 요청 바디 정규화 (키 정렬 + 공백 제거 등: ObjectMapper 설정에 따름) */
    private String canonicalizeInitiateBody(PaymentInitiateRequest req) {

        // 아이템 정규화: menuId 오름차순, 같으면 quantity 오름차순
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

        // 캔노니컬 DTO 구성(필드 순서 고정)
        CanonicalInitiate canonical = CanonicalInitiate.builder()
                .storeId(req.getStoreId())
                .items(normItems)
                .build();

        try {
            return canonicalObjectMapper.writeValueAsString(canonical);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 바디 정규화 실패 시, 의미상 동일 비교가 불가
            throw new CustomException(ErrorCode.REQUEST_CANONICALIZE_FAILED);
        }
    }

    /** Approve 요청 바디 정규화 */
    private String canonicalizeApproveBody(ApproveRequest req) {
        // 방어적 정규화: 공백 전부 제거 (탁상·붙여넣기 실수 대비)
        String raw = req.getPin();
        String normalized = (raw == null) ? null : raw.replaceAll("\\s+", ""); // 공백 모두 제거

        // 숫자 6자리 재검증 (@Valid 통과 후라도 한 번 더 방어)
        if (normalized == null || !normalized.matches("\\d{6}")) {
            throw new CustomException(ErrorCode.PIN_INVALID);
        }

        // 캔노니컬 DTO 생성 (필드 순서 고정)
        CanonicalApprove canonical = CanonicalApprove.builder()
                .pin(normalized)
                .build();

        try {
            return canonicalObjectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new CustomException(ErrorCode.REQUEST_CANONICALIZE_FAILED);
        }
    }

    /** 스냅샷 JSON → DTO */
    private PaymentIntentDetailResponse parseSnapshot(JsonNode node) {
        try {
            return canonicalObjectMapper.treeToValue(node, PaymentIntentDetailResponse.class);
        } catch (Exception e) {
            // 스냅샷 파싱이 불가능하면 리소스 재조회를 시도하도록 위에서 폴백 처리
            throw new CustomException(ErrorCode.RESPONSE_SNAPSHOT_PARSE_FAILED);
        }
    }

    /** intent_public_id로 리소스를 재조회하여 응답 재구성 (스냅샷 없을 때 폴백) */
    private PaymentIntentDetailResponse rebuildFromResource(UUID intentPublicId) {
        return getDetail(intentPublicId);
    }

}