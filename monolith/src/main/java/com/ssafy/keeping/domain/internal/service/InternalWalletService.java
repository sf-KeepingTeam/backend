package com.ssafy.keeping.domain.internal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.domain.idempotency.constant.IdemStatus;
import com.ssafy.keeping.domain.idempotency.dto.IdemBegin;
import com.ssafy.keeping.domain.idempotency.model.IdempotencyKey;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.idempotency.service.IdempotencyService;
import com.ssafy.keeping.domain.internal.dto.FundsCaptureRequest;
import com.ssafy.keeping.domain.internal.dto.FundsResponse;
import com.ssafy.keeping.domain.internal.dto.RefundRequest;
import com.ssafy.keeping.domain.internal.dto.RefundResponse;
import com.ssafy.keeping.domain.internal.dto.WalletBalanceResponse;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.model.TransactionItem;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionItemRepository;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InternalWalletService {

    private final WalletRepository walletRepository;
    private final WalletStoreBalanceRepository balanceRepository;
    private final StoreRepository storeRepository;
    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final MenuRepository menuRepository;
    private final IdempotencyService idempotencyService;

    @Qualifier("canonicalObjectMapper")
    private final ObjectMapper canonicalObjectMapper;

    /**
     * 지갑의 매장별 잔액 조회
     */
    @Transactional(readOnly = true)
    public WalletBalanceResponse getBalance(Long walletId, Long storeId) {
        WalletStoreBalance balance = balanceRepository.findByWalletIdAndStoreId(walletId, storeId)
                .orElse(null);

        BigDecimal balanceAmount = balance != null
                ? BigDecimal.valueOf(balance.getBalance())
                : BigDecimal.ZERO;

        return WalletBalanceResponse.builder()
                .walletId(walletId)
                .storeId(storeId)
                .balance(balanceAmount)
                .build();
    }

    /**
     * 자금 캡처 (멱등성 보장)
     * Idempotency-Key를 통해 네트워크 재시도 시 중복 결제 방지
     */
    @Transactional
    public IdempotentResult<FundsResponse> captureIdempotent(
            FundsCaptureRequest request,
            String idempotencyKeyHeader) {

        // 1. 멱등성 키 필수 검증
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        // 2. 요청 본문 정규화 + SHA-256 해시
        String canonicalBody = canonicalizeCaptureBody(request);
        byte[] bodyHash = IdempotencyService.sha256(canonicalBody);

        // 3. 멱등키 선점 또는 로드
        UUID keyUuid;
        try {
            keyUuid = UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }

        String path = "/internal/wallets/" + request.getWalletId()
                + "/stores/" + request.getStoreId() + "/capture";

        IdemBegin begin = idempotencyService.beginOrLoad(
                IdemActorType.SYSTEM,
                request.getCustomerId(),
                "POST",
                path,
                keyUuid,
                bodyHash);

        IdempotencyKey slot = begin.getRow();

        // 4. 본문 충돌 검증
        if (idempotencyService.isBodyConflict(slot, bodyHash)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
        }

        // 5. 이미 완료된 요청 → 재생
        if (slot.getStatus() == IdemStatus.DONE) {
            FundsResponse replay = parseSnapshot(slot);
            log.info("멱등성 재생: idempotencyKey={}", idempotencyKeyHeader);
            return IdempotentResult.okReplay(replay);
        }

        // 6. 다른 트랜잭션 진행 중 → 202 + Retry-After
        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return IdempotentResult.acceptedWithRetryAfterSeconds(2);
        }

        // 7. 실제 비즈니스 로직 (기존 capture 로직)
        FundsResponse response = capture(request);

        // 8. 완료 기록 + 스냅샷 저장
        idempotencyService.completeCharge(slot, HttpStatus.OK.value(), response);

        return IdempotentResult.ok(response);
    }

    /**
     * 요청 본문 정규화 (멱등성 해시용)
     */
    private String canonicalizeCaptureBody(FundsCaptureRequest request) {
        try {
            return canonicalObjectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.REQUEST_CANONICALIZE_FAILED);
        }
    }

    /**
     * 스냅샷에서 응답 복원
     */
    private FundsResponse parseSnapshot(IdempotencyKey slot) {
        try {
            if (slot.getResponseJson() == null) {
                throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
            }
            return canonicalObjectMapper.treeToValue(slot.getResponseJson(), FundsResponse.class);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.RESPONSE_SNAPSHOT_PARSE_FAILED);
        }
    }

    /**
     * 자금 캡처 (결제 시 잔액 차감 + 거래 내역 생성)
     */
    @Transactional
    public FundsResponse capture(FundsCaptureRequest request) {
        Long walletId = request.getWalletId();
        Long storeId = request.getStoreId();
        Long customerId = request.getCustomerId();
        Long amount = request.getAmount();

        // 1. 엔티티 조회
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        Customer customer = customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // 2. 잔액 조회 (행잠금 - 3초 타임아웃)
        WalletStoreBalance balance;
        try {
            balance = balanceRepository.lockByWalletIdAndStoreId(walletId, storeId)
                    .orElse(null);
        } catch (PessimisticLockException | LockTimeoutException e) {
            log.warn("락 타임아웃: walletId={}, storeId={}, 다른 결제가 진행 중", walletId, storeId);
            return FundsResponse.paymentInProgress();
        }

        if (balance == null || balance.getBalance() < amount) {
            log.warn("잔액 부족: walletId={}, storeId={}, required={}, actual={}",
                    walletId, storeId, amount, balance != null ? balance.getBalance() : 0);
            return FundsResponse.insufficient();
        }

        // 3. 잔액 차감
        balance.subtractBalance(amount);

        // 4. 거래 내역 생성
        Transaction transaction = transactionRepository.save(
                Transaction.builder()
                        .wallet(wallet)
                        .customer(customer)
                        .store(store)
                        .transactionType(TransactionType.USE)
                        .amount(amount)
                        .build()
        );

        // 5. 거래 항목 생성
        if (request.getItems() != null && !request.getItems().isEmpty()) {
            // menuId 모아서 한 번에 조회
            List<Long> menuIds = request.getItems().stream()
                    .map(FundsCaptureRequest.ItemSnapshot::getMenuId)
                    .filter(Objects::nonNull)
                    .toList();

            Map<Long, Menu> menuMap = menuRepository.findAllById(menuIds).stream()
                    .collect(Collectors.toMap(Menu::getMenuId, menu -> menu));

            // TransactionItem 리스트 생성 후 한 번에 저장
            List<TransactionItem> txItems = request.getItems().stream()
                    .map(item -> TransactionItem.of(
                            transaction,
                            storeId,
                            item.getMenuId() != null ? menuMap.get(item.getMenuId()) : null,
                            item.getMenuName(),
                            item.getUnitPrice(),
                            item.getQuantity()
                    ))
                    .toList();

            transactionItemRepository.saveAll(txItems);
        }

        log.info("자금 캡처 완료: walletId={}, storeId={}, amount={}, txId={}",
                walletId, storeId, amount, transaction.getTransactionId());

        return FundsResponse.ok(transaction.getTransactionId());
    }

    /**
     * 잔액 복원 (결제 취소 시)
     */
    @Transactional
    public void restore(Long walletId, Long storeId, Long amount) {
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        // 잔액 조회 (행잠금 - 3초 타임아웃)
        WalletStoreBalance balance;
        try {
            balance = balanceRepository.lockByWalletIdAndStoreId(walletId, storeId)
                    .orElseGet(() -> balanceRepository.save(
                            WalletStoreBalance.builder()
                                    .wallet(wallet)
                                    .store(store)
                                    .balance(0L)
                                    .build()
                    ));
        } catch (PessimisticLockException | LockTimeoutException e) {
            log.warn("복원 중 락 타임아웃: walletId={}, storeId={}, 다른 결제가 진행 중", walletId, storeId);
            throw new CustomException(ErrorCode.PAYMENT_IN_PROGRESS);
        }

        // 잔액 복원
        balance.addBalance(amount);

        log.info("잔액 복원 완료: walletId={}, storeId={}, amount={}", walletId, storeId, amount);
    }

    /**
     * 환불 처리 (멱등성 보장)
     * Idempotency-Key를 통해 중복 환불 방지
     */
    @Transactional
    public IdempotentResult<RefundResponse> processRefundIdempotent(
            Long walletId,
            RefundRequest request,
            String idempotencyKeyHeader) {

        // 1. 멱등성 키 필수 검증
        if (idempotencyKeyHeader == null || idempotencyKeyHeader.isBlank()) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_REQUIRED);
        }

        // 2. 요청 본문 정규화 + SHA-256 해시
        String canonicalBody = canonicalizeRefundBody(request);
        byte[] bodyHash = IdempotencyService.sha256(canonicalBody);

        // 3. 멱등키 선점 또는 로드
        UUID keyUuid;
        try {
            keyUuid = UUID.fromString(idempotencyKeyHeader);
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_KEY_INVALID);
        }

        String path = "/internal/wallets/" + walletId + "/refund";

        IdemBegin begin = idempotencyService.beginOrLoad(
                IdemActorType.SYSTEM,
                0L, // 시스템 호출이므로 actorId는 0
                "POST",
                path,
                keyUuid,
                bodyHash);

        IdempotencyKey slot = begin.getRow();

        // 4. 본문 충돌 검증
        if (idempotencyService.isBodyConflict(slot, bodyHash)) {
            throw new CustomException(ErrorCode.IDEMPOTENCY_BODY_CONFLICT);
        }

        // 5. 이미 완료된 요청 → 재생
        if (slot.getStatus() == IdemStatus.DONE) {
            RefundResponse replay = parseRefundSnapshot(slot);
            log.info("환불 멱등성 재생: idempotencyKey={}", idempotencyKeyHeader);
            return IdempotentResult.okReplay(replay);
        }

        // 6. 다른 트랜잭션 진행 중 → 202 + Retry-After
        if (!begin.isCreated() && slot.getStatus() == IdemStatus.IN_PROGRESS) {
            return IdempotentResult.acceptedWithRetryAfterSeconds(2);
        }

        // 7. 실제 환불 로직
        RefundResponse response = processRefund(walletId, request);

        // 8. 완료 기록 + 스냅샷 저장
        idempotencyService.completeCharge(slot, HttpStatus.OK.value(), response);

        return IdempotentResult.ok(response);
    }

    /**
     * 환불 요청 본문 정규화
     */
    private String canonicalizeRefundBody(RefundRequest request) {
        try {
            return canonicalObjectMapper.writeValueAsString(request);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.REQUEST_CANONICALIZE_FAILED);
        }
    }

    /**
     * 환불 스냅샷에서 응답 복원
     */
    private RefundResponse parseRefundSnapshot(IdempotencyKey slot) {
        try {
            if (slot.getResponseJson() == null) {
                throw new CustomException(ErrorCode.IDEMPOTENCY_REPLAY_UNAVAILABLE);
            }
            return canonicalObjectMapper.treeToValue(slot.getResponseJson(), RefundResponse.class);
        } catch (Exception e) {
            throw new CustomException(ErrorCode.RESPONSE_SNAPSHOT_PARSE_FAILED);
        }
    }

    /**
     * 실제 환불 처리
     * - 잔액 복원 + 환불 거래 내역 생성
     */
    @Transactional
    public RefundResponse processRefund(Long walletId, RefundRequest request) {
        Long storeId = request.getStoreId();
        Long amount = request.getAmount();
        Long originalTransactionId = request.getOriginalTransactionId();
        String reason = request.getReason();

        // 1. 엔티티 조회
        Wallet wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        // 2. 원본 거래 확인 (선택적)
        if (originalTransactionId != null) {
            Transaction originalTx = transactionRepository.findById(originalTransactionId)
                    .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

            if (!originalTx.getWallet().getWalletId().equals(walletId)) {
                return RefundResponse.failed("원본 거래의 지갑 ID가 일치하지 않습니다");
            }
        }

        // 3. 잔액 복원 (행잠금)
        WalletStoreBalance balance;
        try {
            balance = balanceRepository.lockByWalletIdAndStoreId(walletId, storeId)
                    .orElseGet(() -> balanceRepository.save(
                            WalletStoreBalance.builder()
                                    .wallet(wallet)
                                    .store(store)
                                    .balance(0L)
                                    .build()
                    ));
        } catch (PessimisticLockException | LockTimeoutException e) {
            log.warn("환불 중 락 타임아웃: walletId={}, storeId={}", walletId, storeId);
            return RefundResponse.failed("다른 결제가 진행 중입니다. 잠시 후 다시 시도해주세요.");
        }

        balance.addBalance(amount);

        // 4. 환불 거래 내역 생성
        Transaction refundTx = transactionRepository.save(
                Transaction.builder()
                        .wallet(wallet)
                        .store(store)
                        .transactionType(TransactionType.REFUND)
                        .amount(amount)
                        .build()
        );

        log.info("환불 완료: walletId={}, storeId={}, amount={}, refundTxId={}, reason={}",
                walletId, storeId, amount, refundTx.getTransactionId(), reason);

        return RefundResponse.ok(refundTx.getTransactionId());
    }
}
