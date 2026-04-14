package com.ssafy.keeping.domain.wallet.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ssafy.keeping.domain.group.model.Group;
import com.ssafy.keeping.domain.group.repository.GroupMemberRepository;
import com.ssafy.keeping.domain.group.repository.GroupRepository;
import com.ssafy.keeping.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.constant.LotSourceType;
import com.ssafy.keeping.domain.wallet.constant.LotStatus;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.dto.*;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.ssafy.keeping.global.util.TxUtils.afterCommit;

@Service
@RequiredArgsConstructor
public class WalletService { // 충돌나는 것을 방지해 HS를 붙였으나 추후 합치겠습니다.
    private final StoreRepository storeRepository;
    private final WalletRepository walletRepository;
    private final WalletStoreBalanceRepository balanceRepository;
    private final GroupRepository groupRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final WalletStoreLotRepository lotRepository;
    private final GroupMemberRepository groupMemberRepository;
    private final NotificationService notificationService;

    private final IdempotencyService idempotencyService;
    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;

    @Transactional
    public Wallet createOrGetIndividualWallet(Customer customer) {
        // 이미 있으면 그대로 반환 (회원가입 재시도/중복 호출 대비)
        return walletRepository.findByCustomerAndWalletType(customer, WalletType.INDIVIDUAL)
                .orElseGet(() -> {
                    try {
                        return walletRepository.save(
                                Wallet.builder()
                                        .walletType(WalletType.INDIVIDUAL)
                                        .customer(customer)
                                        .build()
                        );
                    } catch (DataIntegrityViolationException e) {
                        // 동시성으로 동시에 생성 시도한 경우
                        return walletRepository.findByCustomerAndWalletType(customer, WalletType.INDIVIDUAL)
                                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_CREATE_FAILED));
                    }
                });
    }

    public WalletResponseDto createGroupWallet(Group group) {

        Wallet saved = walletRepository.save(
                Wallet.builder()
                        .walletType(WalletType.GROUP)
                        .group(group)
                        .build()
        );

        return new WalletResponseDto(
                saved.getWalletId(), saved.getWalletType(),
                group.getGroupId(),
                new ArrayList<>(),
                saved.getCreatedAt()
        );
    }

    // Group 엔티티가 이미 있는 호출용
    public WalletResponseDto getGroupWallet(Group group) {

        Wallet groupWallet = validGroupWallet(group.getGroupId());

        List<WalletStoreBalanceResponseDto> groupStoreBalanceDtoList =
                Optional.ofNullable(groupWallet.getWalletStoreBalances())
                        .orElseGet(Collections::emptyList)
                        .stream()
                        .map(b -> new WalletStoreBalanceResponseDto(
                                b.getBalanceId(),
                                b.getBalance(),
                                b.getUpdatedAt()
                        ))
                        .toList();

        return new WalletResponseDto(
                groupWallet.getWalletId(),
                groupWallet.getWalletType(),
                group.getGroupId(),
                groupStoreBalanceDtoList,
                groupWallet.getCreatedAt()
        );
    }
    // id만 넘어오는 호출용(검증을 여기서 직접 수행)
    public WalletResponseDto getGroupWallet(Long groupId, Long customerId) {
        validCustomer(customerId);
        Group group = validGroup(groupId);
        Wallet groupWallet = validGroupWallet(group.getGroupId());

        List<WalletStoreBalanceResponseDto> groupStoreBalanceDtoList =
                Optional.ofNullable(groupWallet.getWalletStoreBalances())
                        .orElseGet(Collections::emptyList)
                        .stream()
                        .map(b -> new WalletStoreBalanceResponseDto(
                                b.getBalanceId(),
                                b.getBalance(),
                                b.getUpdatedAt()
                        ))
                        .toList();

        return new WalletResponseDto(
                groupWallet.getWalletId(),
                groupWallet.getWalletType(),
                group.getGroupId(),
                groupStoreBalanceDtoList,
                groupWallet.getCreatedAt()
        );
    }

    @Transactional
    public IdempotentResult<PointShareResponseDto> sharePoints(
            Long groupId, Long userId, Long storeId, String idemKeyHeader, @Valid PointShareRequestDto req) {

        if (req == null) throw new CustomException(ErrorCode.BAD_REQUEST);
        if (idemKeyHeader == null || idemKeyHeader.isBlank())
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);

        // 바디 정규화 + 해시
        String canonicalBody = canonicalizeShareBody(groupId, userId, storeId, req);
        byte[] bodyHash = IdempotencyService.sha256(canonicalBody);

        UUID keyUuid = UUID.fromString(idemKeyHeader);
        String path = "/groups/" + groupId + "/stores/" + storeId;

        IdemBegin begin = idempotencyService.beginOrLoad(
                IdemActorType.CUSTOMER, userId, "POST", path, keyUuid, bodyHash);
        IdempotencyKey slot = begin.getRow();

        // 본문 충돌
        if (idempotencyService.isBodyConflict(slot, bodyHash)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
        }

        // DONE → 재생
        if (slot.getStatus() == IdemStatus.DONE) {
            JsonNode snap = slot.getResponseJson();
            if (snap == null) throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
            PointShareResponseDto replay = parseSnapshot(snap);
            return IdempotentResult.okReplay(replay);
        }

        // 타 트랜잭션 IN_PROGRESS 선점 → 202
        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return IdempotentResult.acceptedWithRetryAfterSeconds(2);
        }

        // 실제 처리
        PointShareResponseDto created = doSharePoints(groupId, userId, storeId, req);

        // 완료 기록(DONE) + 스냅샷
        idempotencyService.completeCharge(slot, HttpStatus.CREATED.value(), created);

        return IdempotentResult.created(created);
    }

    // ===== 실제 처리 본문  =====
    @Transactional
    protected PointShareResponseDto doSharePoints(Long groupId, Long userId, Long storeId, @Valid PointShareRequestDto req) {
        // 1) 입력·기본 엔티티 조회
        final long shareAmount = req.getShareAmount();
        if (shareAmount <= 0) throw new CustomException(ErrorCode.BAD_REQUEST);

        Customer actor = validCustomer(userId);
        Wallet individual = validWallet(req.getIndividualWalletId());
        Wallet group = validWallet(req.getGroupWalletId());

        if (individual.getWalletType() != WalletType.INDIVIDUAL || group.getWalletType() != WalletType.GROUP)
            throw new CustomException(ErrorCode.BAD_REQUEST);
        validOwnershipAndMembership(userId, groupId, individual, group);

        Store store = validStore(storeId);

        // 2) 잔액 행잠금 조회
        WalletStoreBalance indivBal = balanceRepository.lockByWalletIdAndStoreId(individual.getWalletId(), storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.BEFORE_INDIVIDUAL_CHARGE));
        if (indivBal.getBalance().compareTo(shareAmount) < 0)
            throw new CustomException(ErrorCode.OVER_INDIVIDUAL_POINT);

        WalletStoreBalance groupBal = balanceRepository.lockByWalletIdAndStoreId(group.getWalletId(), storeId)
                .orElseGet(() -> balanceRepository.save(
                        WalletStoreBalance.builder().wallet(group).store(store).balance(0L).build()
                ));

        // 3) LOT 차감 및 수신 LOT 적립(FIFO)
        Long shareLeft = shareAmount;
        List<WalletStoreLot> lots = lotRepository.lockAllByWalletIdAndStoreIdOrderByAcquiredAt(individual.getWalletId(), storeId);
        for (WalletStoreLot src : lots) {
            if (shareLeft == 0) break;
            if (src.isExpired() || src.isFullyUsed()) continue;

            Long movable = Math.min(src.getAmountRemaining(), shareLeft);
            if (movable == 0) continue;

            src.usePoints(movable);               // 개인 LOT 차감
            shareLeft -= movable;

            // 수신 LOT: 동일 origin_charge_tx 기준으로 1개에 누적
            WalletStoreLot dst = lotRepository
                    .findByWalletIdAndStoreIdAndOriginChargeTxIdAndSourceType(
                            group.getWalletId(),
                            storeId,
                            src.getOriginChargeTransaction().getTransactionId(),
                            LotSourceType.TRANSFER_IN
                    )
                    .orElseGet(() -> lotRepository.save(
                            WalletStoreLot.builder()
                                    .wallet(group)
                                    .store(store)
                                    .amountTotal(0L)
                                    .amountRemaining(0L)
                                    .acquiredAt(src.getAcquiredAt())
                                    .expiredAt(src.getExpiredAt())
                                    .sourceType(LotSourceType.TRANSFER_IN)
                                    .contributorWallet(individual)
                                    .lotStatus(LotStatus.ACTIVE)
                                    .originChargeTransaction(src.getOriginChargeTransaction())
                                    .build()
                    ));
            dst.sharePoints(movable); // 총액·잔량 가산
        }
        if (shareLeft != 0) throw new CustomException(ErrorCode.INCONSISTENT_STATE);

        // 4) 잔액 이동
        indivBal.subtractBalance(shareAmount);
        groupBal.addBalance(shareAmount);

        // 5) 거래기록 2건(반드시 store 세팅)
        Transaction txOut = transactionRepository.save(
                Transaction.builder()
                        .wallet(individual)
                        .relatedWallet(group)
                        .customer(actor)
                        .store(store)
                        .transactionType(TransactionType.TRANSFER_OUT) // 출금
                        .amount(shareAmount)
                        .build()
        );
        Transaction txIn = transactionRepository.save(
                Transaction.builder()
                        .wallet(group) // 수신 지갑
                        .relatedWallet(individual)
                        .customer(actor)
                        .store(store)
                        .transactionType(TransactionType.TRANSFER_IN)
                        .amount(shareAmount)
                        .build()
        );

        String message = String.format(
                "%s님이 %s 모임에 %s 가게 point %,d원 공유했습니다.",
                actor.getName(),
                group.getGroup().getGroupName(),
                store.getStoreName(),
                shareAmount
        );

        afterCommit(() -> {
            List<Long> memberIds = groupMemberRepository.findMemberIdsByGroupId(groupId);
            notificationService.sendGroupSharedToMembers(
                    memberIds, NotificationType.GROUP_POINT_SHARED, message
            );
        });

        return new PointShareResponseDto(
                txOut.getTransactionId(), txIn.getTransactionId(),
                individual.getWalletId(), group.getWalletId(), storeId, shareAmount,
                groupBal.getBalance(), indivBal.getBalance(), LocalDateTime.now(), false
        );
    }

    @Transactional
    public IdempotentResult<PointShareResponseDto> reclaimPoints(
            Long groupId, Long userId, Long storeId, String idemKeyHeader, @Valid PointShareRequestDto req) {

        if (req == null) throw new CustomException(ErrorCode.BAD_REQUEST);
        if (idemKeyHeader == null || idemKeyHeader.isBlank())
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);

        String canonicalBody = canonicalizeReclaimBody(groupId, userId, storeId, req);
        byte[] bodyHash = IdempotencyService.sha256(canonicalBody);

        UUID keyUuid = UUID.fromString(idemKeyHeader);
        String path = "/groups/" + groupId + "/stores/" + storeId + "/reclaim";

        IdemBegin begin = idempotencyService.beginOrLoad(
                IdemActorType.CUSTOMER, userId, "POST", path, keyUuid, bodyHash);
        IdempotencyKey slot = begin.getRow();

        if (idempotencyService.isBodyConflict(slot, bodyHash)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
        }

        if (slot.getStatus() == IdemStatus.DONE) {
            PointShareResponseDto replay = parseSnapshot(slot.getResponseJson());
            return IdempotentResult.okReplay(replay);
        }

        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return IdempotentResult.acceptedWithRetryAfterSeconds(2);
        }

        PointShareResponseDto created = doReclaimPoints(groupId, userId, storeId, req);

        idempotencyService.completeCharge(slot, HttpStatus.CREATED.value(), created);
        return IdempotentResult.created(created);
    }

    // 실제 회수 처리
    @Transactional
    protected PointShareResponseDto doReclaimPoints(Long groupId, Long userId, Long storeId, @Valid PointShareRequestDto req) {
        final long amount = req.getShareAmount(); // 재사용
        if (amount <= 0) throw new CustomException(ErrorCode.BAD_REQUEST);

        Customer actor = validCustomer(userId);
        Wallet individual = validWallet(req.getIndividualWalletId());
        Wallet group = validWallet(req.getGroupWalletId());

        if (individual.getWalletType() != WalletType.INDIVIDUAL || group.getWalletType() != WalletType.GROUP)
            throw new CustomException(ErrorCode.BAD_REQUEST);
        validOwnershipAndMembership(userId, groupId, individual, group);

        Store store = validStore(storeId);

        // 잔액 행잠금
        WalletStoreBalance indivBal = balanceRepository.lockByWalletIdAndStoreId(individual.getWalletId(), storeId)
                .orElseGet(() -> balanceRepository.save(
                        WalletStoreBalance.builder().wallet(individual).store(store).balance(0L).build()
                ));
        WalletStoreBalance groupBal = balanceRepository.lockByWalletIdAndStoreId(group.getWalletId(), storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.BEFORE_GROUP_CHARGE)); // 그룹에 해당 매장 잔액이 있어야 함
        if (groupBal.getBalance().compareTo(amount) < 0)
            throw new CustomException(ErrorCode.OVER_GROUP_POINT);

        // LOT 회수: 그룹 → 개인, FIFO
        Long left = amount;
        List<WalletStoreLot> srcLots =
                lotRepository.lockAllByWalletIdAndStoreIdOrderByAcquiredAt(group.getWalletId(), storeId);
        for (WalletStoreLot src : srcLots) {
            if (left == 0) break;
            if (src.isExpired() || src.isFullyUsed()) continue;

            // 개인이 기여한 LOT만 회수하도록 제한(선택). 필요 없으면 이 if 제거.
            if (src.getContributorWallet() != null
                    && !src.getContributorWallet().getWalletId().equals(individual.getWalletId())) {
                continue;
            }

            Long movable = Math.min(src.getAmountRemaining(), left);
            if (movable == 0) continue;

            // 그룹 LOT 차감
            src.usePoints(movable);
            left -= movable;

            // 개인 LOT 누적: origin_charge_tx 기준 1개에 합산
            WalletStoreLot dst = lotRepository
                    .findByWalletIdAndStoreIdAndOriginChargeTxIdAndSourceType(
                            individual.getWalletId(),
                            storeId,
                            src.getOriginChargeTransaction().getTransactionId(),
                            LotSourceType.TRANSFER_IN // 내부 이관은 TRANSFER_IN 재사용
                    )
                    .orElseGet(() -> lotRepository.save(
                            WalletStoreLot.builder()
                                    .wallet(individual)
                                    .store(store)
                                    .amountTotal(0L)
                                    .amountRemaining(0L)
                                    .acquiredAt(src.getAcquiredAt())
                                    .expiredAt(src.getExpiredAt())
                                    .sourceType(LotSourceType.TRANSFER_IN)
                                    .contributorWallet(group) // 출처 표기
                                    .lotStatus(LotStatus.ACTIVE)
                                    .originChargeTransaction(src.getOriginChargeTransaction())
                                    .build()
                    ));
            dst.sharePoints(movable);
        }
        if (left != 0) throw new CustomException(ErrorCode.INCONSISTENT_STATE);

        // 잔액 이동: 그룹 감소, 개인 증가
        groupBal.subtractBalance(amount);
        indivBal.addBalance(amount);

        // 거래 기록 2건
        Transaction txOut = transactionRepository.save(
                Transaction.builder()
                        .wallet(group)
                        .relatedWallet(individual)
                        .customer(actor)
                        .store(store)
                        .transactionType(TransactionType.TRANSFER_OUT)           // 그룹에서 회수
                        .amount(amount)
                        .build()
        );
        Transaction txIn = transactionRepository.save(
                Transaction.builder()
                        .wallet(individual)
                        .relatedWallet(group)
                        .customer(actor)
                        .store(store)
                        .transactionType(TransactionType.TRANSFER_IN)   // 개인으로 유입
                        .amount(amount)
                        .build()
        );

        return new PointShareResponseDto(
                txOut.getTransactionId(), txIn.getTransactionId(),
                individual.getWalletId(), group.getWalletId(), storeId, amount,
                groupBal.getBalance(), indivBal.getBalance(), LocalDateTime.now(), false
        );
    }

    // ===== Helpers =====
    private String canonicalizeShareBody(Long groupId, Long userId, Long storeId, PointShareRequestDto req) {
        // 키 순서 고정 직렬화
        ObjectNode n = canonicalObjectMapper.createObjectNode();
        n.put("groupId", groupId);
        n.put("userId", userId);
        n.put("storeId", storeId);
        n.put("individualWalletId", req.getIndividualWalletId());
        n.put("groupWalletId", req.getGroupWalletId());
        n.put("shareAmount", req.getShareAmount());
        return n.toString();
    }

    // === canonical helpers ===
    private String canonicalizeReclaimBody(Long groupId, Long userId, Long storeId, PointShareRequestDto req) {
        ObjectNode n = canonicalObjectMapper.createObjectNode();
        n.put("groupId", groupId);
        n.put("userId", userId);
        n.put("storeId", storeId);
        n.put("individualWalletId", req.getIndividualWalletId());
        n.put("groupWalletId", req.getGroupWalletId());
        n.put("reclaimAmount", req.getShareAmount()); // 필드 재사용
        return n.toString();
    }

    private PointShareResponseDto parseSnapshot(JsonNode snap) {
        try {
            return canonicalObjectMapper.treeToValue(snap, PointShareResponseDto.class);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
        }
    }


    @Transactional(readOnly = true)
    public AvailablePointResponseDto getReclaimablePoints(Long walletId, Long storeId, Long customerId) {
        Wallet groupWallet = validWallet(walletId);
        Long groupId = groupWallet.getGroup().getGroupId();

        if (!groupMemberRepository.existsMember(groupId, customerId)) throw new CustomException(ErrorCode.ONLY_GROUP_MEMBER);

        long available = getMemberSharedBalanceByStore(walletId, storeId, customerId);

        return new AvailablePointResponseDto(storeId, groupWallet.getWalletId(), customerId, available);
    }

    @Transactional(readOnly = true)
    public long getMemberSharedBalanceByStore(Long groupWalletId, Long storeId, Long customerId) {
        List<WalletStoreLot> lots = lotRepository.findReclaimableByStore(
                groupWalletId, storeId, customerId, LocalDateTime.now());
        return lots.stream().mapToLong(WalletStoreLot::getAmountRemaining).sum();
    }

    @Transactional(readOnly = true)
    public long getMemberSharedBalance(Long groupId, Long customerId) { // 시그니처 변경
        Wallet groupWallet = validGroupWallet(groupId);
        List<WalletStoreLot> lots = lotRepository
                .findActiveByWalletIdAndContributorCustomerId(groupWallet.getWalletId(), customerId);
        return lots.stream().mapToLong(WalletStoreLot::getAmountRemaining).sum();
    }


    @Transactional
    public long settleShareToIndividual(Long groupId, Long customerId) { // 시그니처 변경
        Wallet groupWallet = validGroupWallet(groupId);

        if (!groupMemberRepository.existsMember(groupId, customerId)) {
            throw new CustomException(ErrorCode.ONLY_GROUP_MEMBER);
        }

        List<WalletStoreLot> srcLots = lotRepository
                .findActiveByWalletIdAndContributorCustomerId(groupWallet.getWalletId(), customerId);
        if (srcLots.isEmpty()) return 0L;

        Wallet individual = srcLots.get(0).getContributorWallet();
        if (individual == null || individual.getCustomer() == null ||
                !individual.getCustomer().getCustomerId().equals(customerId)) {
            throw new CustomException(ErrorCode.INCONSISTENT_STATE);
        }

        long totalRefunded = 0L;

        Map<Long, List<WalletStoreLot>> byStore =
                srcLots.stream().collect(Collectors.groupingBy(l -> l.getStore().getStoreId()));

        for (Map.Entry<Long, List<WalletStoreLot>> entry : byStore.entrySet()) {
            Long storeId = entry.getKey();
            Store store = validStore(storeId);

            WalletStoreBalance groupBal = balanceRepository
                    .lockByWalletIdAndStoreId(groupWallet.getWalletId(), storeId)
                    .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
            WalletStoreBalance indivBal = balanceRepository
                    .lockByWalletIdAndStoreId(individual.getWalletId(), storeId)
                    .orElseGet(() -> balanceRepository.save(
                            WalletStoreBalance.builder().wallet(individual).store(store).balance(0L).build()
                    ));

            long movedSum = 0L;

            for (WalletStoreLot src : entry.getValue()) {
                long remain = src.getAmountRemaining();
                if (remain <= 0) continue;

                src.usePoints(remain);

                WalletStoreLot dst = lotRepository
                        .findByWalletIdAndStoreIdAndOriginChargeTxIdAndSourceType(
                                individual.getWalletId(),
                                storeId,
                                src.getOriginChargeTransaction().getTransactionId(),
                                LotSourceType.TRANSFER_IN
                        )
                        .orElseGet(() -> lotRepository.save(
                                WalletStoreLot.builder()
                                        .wallet(individual).store(store)
                                        .amountTotal(0L).amountRemaining(0L)
                                        .acquiredAt(src.getAcquiredAt())
                                        .expiredAt(src.getExpiredAt())
                                        .sourceType(LotSourceType.TRANSFER_IN)
                                        .contributorWallet(groupWallet)
                                        .lotStatus(LotStatus.ACTIVE)
                                        .originChargeTransaction(src.getOriginChargeTransaction())
                                        .build()
                        ));
                dst.sharePoints(remain);

                transactionRepository.save(Transaction.builder()
                        .wallet(individual).relatedWallet(groupWallet)
                        .customer(individual.getCustomer()).store(store)
                        .transactionType(TransactionType.TRANSFER_IN).amount(remain).build());

                transactionRepository.save(Transaction.builder()
                        .wallet(groupWallet).relatedWallet(individual)
                        .customer(individual.getCustomer()).store(store)
                        .transactionType(TransactionType.TRANSFER_OUT).amount(remain).build());

                movedSum += remain;
            }

            if (movedSum > 0) {
                if (groupBal.getBalance() < movedSum) {
                    throw new CustomException(ErrorCode.INCONSISTENT_STATE);
                }
                groupBal.subtractBalance(movedSum);
                indivBal.addBalance(movedSum);
                totalRefunded += movedSum;
            }
        }

        return totalRefunded;
    }

    @Transactional(readOnly = true)
    public long getTotalIndividualBalance(Long customerId) {
        return balanceRepository.sumBalanceByCustomerIdAndType(customerId, WalletType.INDIVIDUAL)
                .orElse(0L);
    }

    @Transactional
    public Map<Long, Long> settleAllMembersShare(Long groupId, List<Long> memberIds) { // 시그니처 변경
        Map<Long, Long> refunded = new LinkedHashMap<>();
        for (Long memberId : memberIds) {
            long amt = settleShareToIndividual(groupId, memberId); // 변경
            refunded.put(memberId, amt);
        }
        return refunded;
    }

    @Transactional(readOnly = true)
    public PersonalWalletBalanceResponseDto getPersonalWalletBalance(Long customerId, Pageable pageable) {
        Customer customer = validCustomer(customerId);
        Wallet personalWallet = walletRepository.findByCustomerAndWalletType(customer, WalletType.INDIVIDUAL)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Page<WalletStoreBalance> page = balanceRepository.findPersonalWalletBalancesByCustomerId(customerId, pageable);

        List<WalletStoreBalanceDetailDto> storeBalances = page.getContent().stream()
                .map(b -> new WalletStoreBalanceDetailDto(
                        b.getStore().getStoreId(),
                        b.getStore().getStoreName(),
                        b.getBalance(),
                        b.getUpdatedAt()))
                .toList();

        return new PersonalWalletBalanceResponseDto(
                customerId,
                personalWallet.getWalletId(),
                storeBalances
        );
    }

    @Transactional(readOnly = true)
    public GroupWalletBalanceResponseDto getGroupWalletBalance(Long groupId, Long customerId, Pageable pageable) {
        validCustomer(customerId);
        Group group = validGroup(groupId);
        if (!groupMemberRepository.existsMember(groupId, customerId))
            throw new CustomException(ErrorCode.ONLY_GROUP_MEMBER);

        Wallet groupWallet = validGroupWallet(groupId);

        Page<WalletStoreBalance> page = balanceRepository.findGroupWalletBalancesByGroupId(groupId, pageable);

        List<WalletStoreBalanceDetailDto> storeBalances = page.getContent().stream()
                .map(b -> new WalletStoreBalanceDetailDto(
                        b.getStore().getStoreId(),
                        b.getStore().getStoreName(),
                        b.getBalance(),
                        b.getUpdatedAt()))
                .toList();

        return new GroupWalletBalanceResponseDto(
                groupId,
                groupWallet.getWalletId(),
                group.getGroupName(),
                storeBalances
        );
    }

    /**
     * 개인지갑 - 특정 가게의 상세 정보 조회
     */
    @Transactional(readOnly = true)
    public WalletStoreDetailResponseDto getPersonalWalletStoreDetail(Long customerId, Long storeId, Pageable pageable) {
        // 1. 고객 및 가게 검증
        Customer customer = validCustomer(customerId);

        Store store = validStore(storeId);                ;

        // 2. 개인지갑 조회
        Wallet personalWallet = walletRepository.findByCustomerAndWalletType(customer, WalletType.INDIVIDUAL)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        // 3. 현재 잔액 조회
        WalletStoreBalance balance = balanceRepository.findByWalletAndStore(personalWallet, store)
                .orElse(WalletStoreBalance.builder()
                        .wallet(personalWallet)
                        .store(store)
                        .balance(0L)
                        .build());

        // 4. 거래내역 조회 (페이징) - 간소화
        Page<Transaction> transactions = transactionRepository
                .findValidTransactionsByCustomerAndStore(customerId, storeId, pageable);

        // 5. Transaction을 DTO로 변환
        Page<WalletStoreTransactionDetailDto> transactionDtos = transactions
                .map(WalletStoreTransactionDetailDto::from);

        // 6. 응답 DTO 조립 (간소화)
        return new WalletStoreDetailResponseDto(
                store.getStoreId(),
                store.getStoreName(),
                balance.getBalance(),
                transactionDtos
        );
    }

    /**
     * 개인 지갑 + 모임 지갑들 통합 조회
     */
    @Transactional(readOnly = true)
    public BothWalletBalanceResponseDto getBothWalletBalance(Long customerId, Pageable pageable) {
        // 1. 개인 지갑 조회
        PersonalWalletBalanceResponseDto personalWallet = getPersonalWalletBalance(customerId, pageable);

        // 2. 사용자가 속한 모든 그룹 조회
        List<GroupWalletBalanceResponseDto> groupWallets = groupMemberRepository
                .findMemberGroupsByCustomerId(customerId)
                .stream()
                .map(groupId -> getGroupWalletBalance(groupId, customerId, pageable))
                .toList();

        return new BothWalletBalanceResponseDto(personalWallet, groupWallets);
    }

    /**
     * 모임지갑 - 특정 가게의 상세 정보 조회
     */
    @Transactional(readOnly = true)
    public WalletStoreDetailResponseDto getGroupWalletStoreDetail(Long groupId, Long customerId, Long storeId, Pageable pageable) {
        // 1. 고객, 모임, 가게 검증
        Customer customer = validCustomer(customerId);

        Group group = validGroup(groupId);

        Store store = validStore(storeId);

        // 2. 모임 멤버십 검증
        if (!groupMemberRepository.existsMember(groupId, customerId)) {
            throw new CustomException(ErrorCode.ONLY_GROUP_MEMBER);
        }

        // 3. 모임지갑 조회
        Wallet groupWallet = validGroupWallet(groupId);

        // 4. 현재 잔액 조회
        WalletStoreBalance balance = balanceRepository.findByWalletAndStore(groupWallet, store)
                .orElse(WalletStoreBalance.builder()
                        .wallet(groupWallet)
                        .store(store)
                        .balance(0L)
                        .build());

        // 5. 거래내역 조회 (페이징) - 간소화
        Page<Transaction> transactions = transactionRepository
                .findValidTransactionsByGroupAndStore(groupId, storeId, pageable);

        // 6. Transaction을 DTO로 변환
        Page<WalletStoreTransactionDetailDto> transactionDtos = transactions
                .map(WalletStoreTransactionDetailDto::from);

        // 7. 응답 DTO 조립 (간소화)
        return new WalletStoreDetailResponseDto(
                store.getStoreId(),
                store.getStoreName(),
                balance.getBalance(),
                transactionDtos
        );
    }

    // ===== Validation Helpers =====
    private Customer validCustomer(Long customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private Group validGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.GROUP_NOT_FOUND));
    }

    private Store validStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));
    }

    private Wallet validWallet(Long walletId) {
        return walletRepository.findById(walletId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
    }

    private Wallet validGroupWallet(Long groupId) {
        return walletRepository.findByGroupId(groupId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
    }

    private void validMembership(Long groupId, Long customerId) {
        if (!groupMemberRepository.existsMember(groupId, customerId)) {
            throw new CustomException(ErrorCode.ONLY_GROUP_MEMBER);
        }
    }

    private void validOwnershipAndMembership(Long userId, Long groupId, Wallet individual, Wallet group) {
        if (individual.getCustomer() == null || !individual.getCustomer().getCustomerId().equals(userId))
            throw new CustomException(ErrorCode.BAD_REQUEST);
        if (group.getGroup() == null || !group.getGroup().getGroupId().equals(groupId))
            throw new CustomException(ErrorCode.BAD_REQUEST);
        validMembership(groupId, userId);
    }
}