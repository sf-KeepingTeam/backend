package com.ssafy.keeping.qr.domain.intent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.common.exception.CustomException;
import com.ssafy.keeping.qr.common.exception.ErrorCode;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.qr.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.qr.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.qr.domain.idempotency.repository.IdempotencyKeyRepository;
import com.ssafy.keeping.qr.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.qr.domain.intent.constant.PaymentStatus;
import com.ssafy.keeping.qr.domain.intent.dto.ApprovePhaseAResult;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentDetailResponse;
import com.ssafy.keeping.qr.domain.intent.dto.PaymentIntentItemView;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntent;
import com.ssafy.keeping.qr.domain.intent.model.PaymentIntentItem;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentItemRepository;
import com.ssafy.keeping.qr.domain.intent.repository.PaymentIntentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * approve() 트랜잭션 경계 분리를 위한 헬퍼 빈.
 *
 * <p>PaymentIntentService 와 별도 빈이므로 Spring AOP 프록시가
 * {@code REQUIRES_NEW} 전파를 정상 적용한다 (self-invocation 회피).
 *
 * <ul>
 *   <li>TX-A ({@link #prepareApproval}): 멱등 슬롯 선점, intent 검증, items 스냅샷
 *   <li>TX-B ({@link #finalizeApproved}): intent 재조회(@Version), 상태 전이, 멱등 완료
 *   <li>TX-B 실패 경로 ({@link #finalizeDeclined} / {@link #finalizeExpired}):
 *       실패 상태 전이만 별도 커밋. 예외는 커밋 후 호출자가 던진다.
 * </ul>
 */
@Slf4j
@Service
public class ApproveTransactionHelper {

    private final PaymentIntentRepository intentRepository;
    private final PaymentIntentItemRepository itemRepository;
    private final IdempotencyService idempotencyService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper canonicalObjectMapper;
    private final Clock clock;

    public ApproveTransactionHelper(
            PaymentIntentRepository intentRepository,
            PaymentIntentItemRepository itemRepository,
            IdempotencyService idempotencyService,
            IdempotencyKeyRepository idempotencyKeyRepository,
            @Qualifier("canonicalObjectMapper") ObjectMapper canonicalObjectMapper,
            Clock clock) {
        this.intentRepository = intentRepository;
        this.itemRepository = itemRepository;
        this.idempotencyService = idempotencyService;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.canonicalObjectMapper = canonicalObjectMapper;
        this.clock = clock;
    }

    // ═══════════════════════════════════════════════════════════════
    //  TX-A : 멱등 슬롯 선점 + intent 검증 + items 스냅샷
    // ═══════════════════════════════════════════════════════════════

    /**
     * TX-A. 커밋 후 DB 커넥션을 반납한다.
     *
     * @return 조기 반환(replay/202)이면 {@code earlyReturn != null},
     *         정상이면 스칼라 스냅샷이 채워진 결과.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApprovePhaseAResult prepareApproval(
            UUID intentPublicId,
            String idempotencyKeyHeader,
            Long customerId,
            byte[] bodyHash) {

        long t0 = System.currentTimeMillis();

        // ── 멱등 게이트 ──
        UUID keyUuid;
        try {
            keyUuid = UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException ex) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }

        String path = "/payments/" + intentPublicId + "/approve";
        IdemBegin begin = idempotencyService.beginOrLoad(
                IdemActorType.CUSTOMER, customerId, "POST", path, keyUuid, bodyHash);

        IdempotencyKey slot = begin.getRow();

        if (idempotencyService.isBodyConflict(slot, bodyHash)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
        }

        // DONE -> replay
        if (slot.getStatus() == IdemStatus.DONE) {
            PaymentIntentDetailResponse replay = replayFromSlot(slot);
            return ApprovePhaseAResult.builder()
                    .earlyReturn(IdempotentResult.okReplay(replay))
                    .build();
        }

        // 이미 IN_PROGRESS (다른 요청이 처리 중)
        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return ApprovePhaseAResult.builder()
                    .earlyReturn(IdempotentResult.acceptedWithRetryAfterSeconds(2))
                    .build();
        }

        // ── intent 조회 + 검증 ──
        PaymentIntent intent = intentRepository.findByPublicId(intentPublicId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));

        LocalDateTime now = LocalDateTime.now(clock);

        if (intent.getStatus() != PaymentStatus.PENDING) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_STATUS_CONFLICT);
        }
        if (intent.getExpiresAt() != null && now.isAfter(intent.getExpiresAt())) {
            // EXPIRED 전이를 이 트랜잭션 안에서 하지 않는다.
            // 여기서 markExpired 후 CustomException 을 던지면 TX-A 가 롤백되어
            // 만료 전이가 DB 에 남지 않는다 (finalizeDeclined 와 같은 롤백 버그).
            // 호출자가 TX-A 커밋 후 finalizeExpired(REQUIRES_NEW) 로 전이시키고 예외를 던진다.
            log.info("[APPROVE_PHASE] phase=TX_A_EXPIRED intentId={}", intent.getIntentId());
            return ApprovePhaseAResult.builder()
                    .expired(true)
                    .intentId(intent.getIntentId())
                    .intentPublicId(intent.getPublicId())
                    .customerId(intent.getCustomerId())
                    .expiresAt(intent.getExpiresAt())
                    .idemSlotId(slot.getId())
                    .build();
        }
        if (!Objects.equals(intent.getCustomerId(), customerId)) {
            throw new CustomException(ErrorCode.PAYMENT_INTENT_OWNER_MISMATCH);
        }

        // ── items 스냅샷 (메모리 복사) ──
        List<PaymentIntentItem> items = itemRepository.findByIntent_IntentId(intent.getIntentId());
        List<PaymentIntentItemView> itemViews = items.stream()
                .map(this::toItemView)
                .collect(Collectors.toList());

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[APPROVE_PHASE] phase=TX_A intentId={} elapsedMs={}", intent.getIntentId(), elapsed);

        return ApprovePhaseAResult.builder()
                .intentId(intent.getIntentId())
                .intentPublicId(intent.getPublicId())
                .customerId(intent.getCustomerId())
                .walletId(intent.getWalletId())
                .storeId(intent.getStoreId())
                .amount(intent.getAmount())
                .expiresAt(intent.getExpiresAt())
                .idemSlotId(slot.getId())
                .itemViews(itemViews)
                .build();
    }

    // ═══════════════════════════════════════════════════════════════
    //  TX-B : intent 재조회 + 상태 전이 + 멱등 완료
    // ═══════════════════════════════════════════════════════════════

    /**
     * TX-B 성공 경로. intent 를 APPROVED 로 전이하고 멱등 슬롯을 DONE 으로 닫는다.
     *
     * <p>intent 를 ID 로 재조회하므로 {@code @Version} 낙관적 락이 동시 수정을 감지한다.
     *
     * @return 최종 응답 DTO
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentIntentDetailResponse finalizeApproved(
            Long intentId,
            Long idemSlotId,
            List<PaymentIntentItemView> itemViews) {

        long t0 = System.currentTimeMillis();

        // intent 재조회 (@Version 낙관적 락)
        PaymentIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));

        // 이미 APPROVED 면 멱등 재생 (TX-B 중복 호출 방어)
        if (intent.getStatus() == PaymentStatus.APPROVED) {
            log.info("[APPROVE_PHASE] phase=TX_B_IDEMPOTENT intentId={}", intentId);
            return PaymentIntentDetailResponse.from(intent, itemViews);
        }

        LocalDateTime now = LocalDateTime.now(clock);
        intent.markApproved(now);
        // JPA dirty checking 으로 flush

        PaymentIntentDetailResponse res = PaymentIntentDetailResponse.from(intent, itemViews);

        // 멱등 슬롯 완료
        IdempotencyKey slot = idempotencyKeyRepository.findById(idemSlotId)
                .orElse(null);
        if (slot != null) {
            try {
                idempotencyService.completeStrict(slot, HttpStatus.OK.value(), res, intent.getPublicId());
            } catch (JsonProcessingException e) {
                idempotencyService.completeWithoutSnapshot(slot, HttpStatus.OK.value(), intent.getPublicId());
            }
        }

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[APPROVE_PHASE] phase=TX_B_APPROVED intentId={} elapsedMs={}", intentId, elapsed);

        return res;
    }

    /**
     * TX-B 실패 경로. intent 를 DECLINED 로 전이한다.
     *
     * <p>CustomException 을 TX-B 안에서 던지지 않는다 -- 커밋 후
     * 호출자(orchestrator)가 예외를 던진다.  이로써 기존 markDeclined 롤백 버그를 해소한다.
     *
     * @return 에러 코드 문자열 (호출자가 적절한 CustomException 을 던지는 데 사용)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeDeclined(Long intentId, Long idemSlotId) {

        long t0 = System.currentTimeMillis();

        PaymentIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));

        // 이미 DECLINED / EXPIRED 등이면 중복 처리 방지
        if (intent.getStatus() != PaymentStatus.PENDING) {
            log.info("[APPROVE_PHASE] phase=TX_B_DECLINE_SKIP intentId={} currentStatus={}",
                    intentId, intent.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        intent.markDeclined(now);
        // JPA dirty checking 으로 flush

        // 멱등 슬롯은 IN_PROGRESS 상태로 남긴다 -- cleanupStalledInProgress(5분) 가 정리.
        // DECLINED 결과를 스냅샷으로 저장하지 않는다 (실패 응답 replay 불필요).

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[APPROVE_PHASE] phase=TX_B_DECLINED intentId={} elapsedMs={}", intentId, elapsed);
    }

    /**
     * 만료 확정 경로. intent 를 EXPIRED 로 전이한다.
     *
     * <p>{@link #finalizeDeclined} 와 동일한 구조다. CustomException 을 이 트랜잭션 안에서
     * 던지지 않는다 -- 커밋 후 호출자가 {@code PAYMENT_INTENT_EXPIRED} 를 던진다.
     *
     * <p>기존에는 검증 트랜잭션 안에서 {@code markExpired + save} 직후 예외를 던져
     * 만료 전이가 함께 롤백되었다(intent 가 PENDING 으로 남음). 이 메서드는
     * {@code REQUIRES_NEW} 로 별도 커밋해 그 문제를 해소한다.
     *
     * @param idemSlotId 멱등 슬롯 식별자 (현재는 로깅/대칭성 목적. finalizeDeclined 와 동일하게
     *                   슬롯은 IN_PROGRESS 로 남기고 cleanupStalledInProgress 가 정리한다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeExpired(Long intentId, Long idemSlotId) {

        long t0 = System.currentTimeMillis();

        PaymentIntent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new CustomException(ErrorCode.PAYMENT_INTENT_NOT_FOUND));

        // 이미 EXPIRED / DECLINED 등이면 중복 처리 방지
        if (intent.getStatus() != PaymentStatus.PENDING) {
            log.info("[APPROVE_PHASE] phase=TX_B_EXPIRE_SKIP intentId={} currentStatus={}",
                    intentId, intent.getStatus());
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        intent.markExpired(now);
        // JPA dirty checking 으로 flush

        // 멱등 슬롯은 IN_PROGRESS 상태로 남긴다 -- cleanupStalledInProgress(5분) 가 정리.

        long elapsed = System.currentTimeMillis() - t0;
        log.info("[APPROVE_PHASE] phase=TX_B_EXPIRED intentId={} elapsedMs={}", intentId, elapsed);
    }

    // ═══════════════════════════════════════════════════════════════
    //  내부 유틸
    // ═══════════════════════════════════════════════════════════════

    private PaymentIntentDetailResponse replayFromSlot(IdempotencyKey slot) {
        JsonNode node = slot.getResponseJson();
        if (node != null && !node.isNull()) {
            try {
                return canonicalObjectMapper.treeToValue(node, PaymentIntentDetailResponse.class);
            } catch (Exception e) {
                // fallthrough to rebuild
            }
        }
        if (slot.getIntentPublicId() != null) {
            PaymentIntent intent = intentRepository.findByPublicId(slot.getIntentPublicId())
                    .orElseThrow(() -> new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE));
            List<PaymentIntentItem> items = itemRepository.findByIntent_IntentId(intent.getIntentId());
            List<PaymentIntentItemView> views = items.stream()
                    .map(this::toItemView)
                    .collect(Collectors.toList());
            return PaymentIntentDetailResponse.from(intent, views);
        }
        throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
    }

    private PaymentIntentItemView toItemView(PaymentIntentItem it) {
        long line = (it.getLineTotal() != null)
                ? it.getLineTotal()
                : it.getUnitPriceSnap() * it.getQuantity();
        return PaymentIntentItemView.builder()
                .menuId(it.getMenuId())
                .name(it.getMenuNameSnap())
                .unitPrice(it.getUnitPriceSnap())
                .quantity(it.getQuantity())
                .lineTotal(line)
                .build();
    }
}
