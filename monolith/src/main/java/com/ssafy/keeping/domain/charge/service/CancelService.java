package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.dto.request.CancelRequestDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelListResponseDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelResponseDto;
import com.ssafy.keeping.domain.payment.toss.TossPaymentClient;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelRequest;
import com.ssafy.keeping.domain.payment.toss.dto.TossCancelResponse;
import com.ssafy.keeping.domain.payment.transactions.constant.TransactionType;
import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.payment.transactions.repository.TransactionRepository;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreLotRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 선결제 취소 서비스 - 간소화 버전
 * SettlementTask 의존성 제거
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class CancelService {

    private final CustomerRepository customerRepository;
    private final TransactionRepository transactionRepository;
    private final WalletStoreLotRepository walletStoreLotRepository;
    private final WalletStoreBalanceRepository walletStoreBalanceRepository;
    private final TossPaymentClient tossPaymentClient;

    /**
     * 취소 가능한 거래 목록 조회 (페이지네이션)
     * 간소화: 일단 비활성화 (나중에 필요시 구현)
     */
    public Page<CancelListResponseDto> getCancelableTransactions(Long customerId, Pageable pageable) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));

        log.info("[취소] 취소 가능한 거래 목록 조회 - 고객ID: {}", customerId);

        // TODO: 취소 가능한 거래 목록 조회 기능 추가 필요
        return Page.empty(pageable);
    }

    /**
     * 결제 취소 처리 - 간소화 버전
     */
    @Transactional
    public CancelResponseDto cancelPayment(Long customerId, CancelRequestDto requestDto) {
        String paymentKey = resolvePaymentKey(requestDto);

        log.info("[취소] 시작 - 고객ID: {}, paymentKey: {}", customerId, paymentKey);

        // 1. 거래 조회 및 검증
        Transaction originalTransaction = validateCancellation(customerId, paymentKey);

        // 2. 토스페이먼츠 취소 API 호출
        TossCancelRequest tossCancelRequest = TossCancelRequest.builder()
                .cancelReason(requestDto.getCancelReason())
                .cancelAmount(requestDto.getCancelAmount() != null
                        ? requestDto.getCancelAmount()
                        : originalTransaction.getAmount())
                .build();

        log.info("[취소] 토스 API 호출 - paymentKey: {}, cancelAmount: {}",
                paymentKey, tossCancelRequest.getCancelAmount());

        TossCancelResponse tossResponse = tossPaymentClient.cancelPayment(
                paymentKey, tossCancelRequest);

        if (!tossResponse.isSuccess()) {
            log.error("[취소] 토스 취소 실패 - code: {}, message: {}",
                    tossResponse.getCode(), tossResponse.getMessage());
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }

        log.info("[취소] 토스 취소 성공 - paymentKey: {}", tossResponse.getPaymentKey());

        // 3. DB 반영 (포인트 차감)
        return saveCancelAndRefundPoints(originalTransaction, tossResponse);
    }

    /**
     * paymentKey 확인
     */
    private String resolvePaymentKey(CancelRequestDto requestDto) {
        if (requestDto.getPaymentKey() != null && !requestDto.getPaymentKey().isBlank()) {
            return requestDto.getPaymentKey();
        }
        if (requestDto.getTransactionUniqueNo() != null && !requestDto.getTransactionUniqueNo().isBlank()) {
            return requestDto.getTransactionUniqueNo();
        }
        throw new CustomException(ErrorCode.INVALID_REQUEST);
    }

    /**
     * 취소 가능 검증 - 비관적 락 적용
     */
    private Transaction validateCancellation(Long customerId, String paymentKey) {
        // 1. 거래 조회
        Transaction originalTransaction = transactionRepository
                .findByTransactionUniqueNo(paymentKey)
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 2. 본인 거래 확인
        if (!originalTransaction.getCustomer().getCustomerId().equals(customerId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);
        }

        // 3. 이미 취소된 거래인지 확인
        if (originalTransaction.getRefTransaction() != null) {
            throw new CustomException(ErrorCode.CANCEL_NOT_AVAILABLE);
        }

        // 4. 포인트가 모두 남아있는지 확인 (🔒 비관적 락)
        WalletStoreLot lot = walletStoreLotRepository
                .findByOriginChargeTransactionWithLock(originalTransaction)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        if (!lot.getAmountRemaining().equals(lot.getAmountTotal())) {
            log.warn("[취소] 불가 - 포인트 일부 사용됨. 총: {}, 잔여: {}",
                    lot.getAmountTotal(), lot.getAmountRemaining());
            throw new CustomException(ErrorCode.CANCEL_NOT_AVAILABLE);
        }

        log.info("[취소] 검증 완료 (락 획득) - 거래ID: {}, 금액: {}",
                originalTransaction.getTransactionId(), originalTransaction.getAmount());

        return originalTransaction;
    }

    /**
     * 취소 후 DB 업데이트 - 간소화 버전
     */
    private CancelResponseDto saveCancelAndRefundPoints(
            Transaction originalTransaction,
            TossCancelResponse tossResponse) {

        LocalDateTime now = LocalDateTime.now();

        // 1. 취소 Transaction 생성
        Transaction cancelTransaction = Transaction.builder()
                .wallet(originalTransaction.getWallet())
                .customer(originalTransaction.getCustomer())
                .store(originalTransaction.getStore())
                .transactionType(TransactionType.CANCEL_CHARGE)
                .amount(originalTransaction.getAmount())
                .transactionUniqueNo(originalTransaction.getTransactionUniqueNo())
                .refTransaction(originalTransaction)
                .build();
        cancelTransaction = transactionRepository.save(cancelTransaction);

        // 2. WalletStoreLot 취소 처리
        WalletStoreLot lot = walletStoreLotRepository
                .findByOriginChargeTransaction(originalTransaction)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        lot.setAmountRemaining(0L);
        lot.setCancelTransaction(cancelTransaction);
        lot.setCanceledAt(now);
        lot.markAsCanceled();

        // 3. WalletStoreBalance 차감
        WalletStoreBalance balance = walletStoreBalanceRepository
                .findByWalletAndStore(originalTransaction.getWallet(), originalTransaction.getStore())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        balance.subtractBalance(lot.getAmountTotal());

        log.info("[취소] 완료 - 고객ID: {}, 원금: {}원, 보너스: {}원, 총회수: {}P",
                originalTransaction.getCustomer().getCustomerId(),
                originalTransaction.getAmount(),
                lot.getAmountTotal() - originalTransaction.getAmount(),
                lot.getAmountTotal());

        // 4. 응답 생성
        return CancelResponseDto.builder()
                .cancelTransactionId(cancelTransaction.getTransactionId())
                .transactionUniqueNo(originalTransaction.getTransactionUniqueNo())
                .cancelAmount(originalTransaction.getAmount())
                .cancelTime(now)
                .remainingBalance(balance.getBalance())
                .build();
    }

    /**
     * Transaction을 CancelListResponseDto로 변환
     */
    private CancelListResponseDto convertToDto(Transaction transaction) {
        WalletStoreLot lot = walletStoreLotRepository
                .findByOriginChargeTransaction(transaction)
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        return CancelListResponseDto.builder()
                .transactionUniqueNo(transaction.getTransactionUniqueNo())
                .storeName(transaction.getStore().getStoreName())
                .paymentAmount(transaction.getAmount())
                .transactionTime(transaction.getCreatedAt())
                .remainingBalance(lot.getAmountRemaining())
                .build();
    }
}