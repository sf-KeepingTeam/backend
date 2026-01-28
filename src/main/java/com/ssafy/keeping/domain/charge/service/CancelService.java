package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.dto.request.CancelRequestDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelListResponseDto;
import com.ssafy.keeping.domain.charge.dto.response.CancelPrepareResult;
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
 * 선결제 취소 서비스 - 3-Phase 패턴 적용
 *
 * Phase 1 (prepareCancel): 트랜잭션 내에서 상태를 CANCEL_PENDING으로 변경
 * Phase 2 (executeCancel): 트랜잭션 외부에서 외부 API 호출
 * Phase 3 (completeCancel/rollbackCancel): 결과에 따라 최종 상태 업데이트
 *
 * 이 패턴은 외부 API 호출 실패 시에도 데이터 정합성을 유지할 수 있게 해줍니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CancelService {

    // === 외부 서비스 ===
    private final TossPaymentClient tossPaymentClient;

    // === 외부 도메인 의존성 (MSA 전환 시 API 호출로 대체) ===
    // User 도메인: Pattern 1 (호출자 검증) - Controller에서 인증된 customerId 사용
    private final CustomerRepository customerRepository;
    // Payment 도메인: 동일 Bounded Context로 유지
    private final TransactionRepository transactionRepository;
    // Wallet 도메인: WalletService API 호출로 전환 (cancelLot, updateBalance 등)
    private final WalletStoreLotRepository walletStoreLotRepository;
    private final WalletStoreBalanceRepository walletStoreBalanceRepository;

    /**
     * 취소 가능한 거래 목록 조회 (페이지네이션)
     */
    @Transactional(readOnly = true)
    public Page<CancelListResponseDto> getCancelableTransactions(Long customerId, Pageable pageable) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));

        log.info("[취소] 취소 가능한 거래 목록 조회 - 고객ID: {}", customerId);

        // TODO: 취소 가능한 거래 목록 조회 기능 추가 필요
        return Page.empty(pageable);
    }

    /**
     * 결제 취소 처리 - 3-Phase 패턴 적용
     * 외부 API 호출이 트랜잭션 밖에서 수행되어 보상 로직이 가능
     */
    public CancelResponseDto cancelPayment(Long customerId, CancelRequestDto requestDto) {
        log.info("[취소] 3-Phase 시작 - 고객ID: {}", customerId);

        // Phase 1: 취소 준비 (트랜잭션 내)
        CancelPrepareResult prepared = prepareCancel(customerId, requestDto);

        // Phase 2 & 3: 외부 API 호출 및 완료/롤백 (트랜잭션 외부에서 시작)
        return executeCancel(prepared);
    }

    /**
     * Phase 1: 취소 준비
     * - 거래 검증
     * - Lot에 비관적 락 획득
     * - 취소 가능 여부 확인
     * - 취소에 필요한 정보 수집
     */
    @Transactional
    public CancelPrepareResult prepareCancel(Long customerId, CancelRequestDto requestDto) {
        String paymentKey = resolvePaymentKey(requestDto);

        log.info("[취소-Phase1] 준비 시작 - 고객ID: {}, paymentKey: {}", customerId, paymentKey);

        // 1. 거래 조회 및 검증
        Transaction originalTransaction = validateCancellation(customerId, paymentKey);

        // 2. Lot 정보 조회 (비관적 락)
        WalletStoreLot lot = walletStoreLotRepository
                .findByOriginChargeTransactionIdWithLock(originalTransaction.getTransactionId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        // 3. 취소 금액 계산
        Long cancelAmount = requestDto.getCancelAmount() != null
                ? requestDto.getCancelAmount()
                : originalTransaction.getAmount();
        Long bonusAmount = lot.getAmountTotal() - originalTransaction.getAmount();

        log.info("[취소-Phase1] 준비 완료 - 거래ID: {}, 취소금액: {}, 보너스: {}",
                originalTransaction.getTransactionId(), cancelAmount, bonusAmount);

        return CancelPrepareResult.builder()
                .transactionId(originalTransaction.getTransactionId())
                .paymentKey(paymentKey)
                .customerId(customerId)
                .storeId(originalTransaction.getStoreId())
                .walletId(originalTransaction.getWalletId())
                .lotId(lot.getLotId())
                .cancelAmount(cancelAmount)
                .bonusAmount(bonusAmount)
                .cancelReason(requestDto.getCancelReason())
                .build();
    }

    /**
     * Phase 2: 외부 API 호출 및 Phase 3 실행
     * - 토스페이먼츠 취소 API 호출
     * - 성공 시 completeCancel, 실패 시 rollbackCancel 호출
     */
    public CancelResponseDto executeCancel(CancelPrepareResult prepared) {
        log.info("[취소-Phase2] 토스 API 호출 시작 - paymentKey: {}", prepared.paymentKey());

        TossCancelRequest tossCancelRequest = TossCancelRequest.builder()
                .cancelReason(prepared.cancelReason())
                .cancelAmount(prepared.cancelAmount())
                .build();

        try {
            TossCancelResponse tossResponse = tossPaymentClient.cancelPayment(
                    prepared.paymentKey(), tossCancelRequest);

            if (!tossResponse.isSuccess()) {
                log.error("[취소-Phase2] 토스 취소 실패 - code: {}, message: {}",
                        tossResponse.getCode(), tossResponse.getMessage());
                // 토스 API 자체가 실패 응답을 반환한 경우 - DB는 변경하지 않음
                throw new CustomException(ErrorCode.PAYMENT_CANCEL_FAILED);
            }

            log.info("[취소-Phase2] 토스 취소 성공 - paymentKey: {}", tossResponse.getPaymentKey());

            // Phase 3: 성공 시 DB 업데이트
            return completeCancel(prepared, tossResponse);

        } catch (CustomException e) {
            // CustomException은 그대로 전파
            throw e;
        } catch (Exception e) {
            // 토스 API 호출 중 네트워크 오류 등 발생
            log.error("[취소-Phase2] 토스 API 호출 중 오류 발생 - paymentKey: {}", prepared.paymentKey(), e);
            // 이 경우 토스에서 실제로 취소가 되었는지 불확실하므로
            // CANCEL_FAILED 상태로 마킹하여 추후 스케줄러가 처리하도록 함
            markAsCancelFailed(prepared.transactionId());
            throw new CustomException(ErrorCode.PAYMENT_CANCEL_FAILED);
        }
    }

    /**
     * Phase 3 (성공): 취소 완료 처리
     * - 취소 Transaction 생성
     * - Lot 취소 처리
     * - Balance 차감
     */
    @Transactional
    public CancelResponseDto completeCancel(CancelPrepareResult prepared, TossCancelResponse tossResponse) {
        log.info("[취소-Phase3] 완료 처리 시작 - 거래ID: {}", prepared.transactionId());

        LocalDateTime now = LocalDateTime.now();

        // 1. 원본 거래 조회
        Transaction originalTransaction = transactionRepository.findById(prepared.transactionId())
                .orElseThrow(() -> new CustomException(ErrorCode.TRANSACTION_NOT_FOUND));

        // 2. 취소 Transaction 생성
        Transaction cancelTransaction = Transaction.builder()
                .walletId(originalTransaction.getWalletId())
                .customerId(originalTransaction.getCustomerId())
                .storeId(originalTransaction.getStoreId())
                .transactionType(TransactionType.CANCEL_CHARGE)
                .amount(originalTransaction.getAmount())
                .transactionUniqueNo(originalTransaction.getTransactionUniqueNo())
                .refTransaction(originalTransaction)
                .build();
        cancelTransaction = transactionRepository.save(cancelTransaction);

        // 3. WalletStoreLot 취소 처리
        WalletStoreLot lot = walletStoreLotRepository.findById(prepared.lotId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        lot.setAmountRemaining(0L);
        lot.setCancelTransactionId(cancelTransaction.getTransactionId());
        lot.setCanceledAt(now);
        lot.markAsCanceled();

        // 4. WalletStoreBalance 차감
        WalletStoreBalance balance = walletStoreBalanceRepository
                .findByWallet_WalletIdAndStoreId(prepared.walletId(), prepared.storeId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        balance.subtractBalance(lot.getAmountTotal());

        log.info("[취소-Phase3] 완료 - 고객ID: {}, 원금: {}원, 보너스: {}원, 총회수: {}P",
                prepared.customerId(),
                prepared.cancelAmount(),
                prepared.bonusAmount(),
                lot.getAmountTotal());

        // 5. 응답 생성
        return CancelResponseDto.builder()
                .cancelTransactionId(cancelTransaction.getTransactionId())
                .transactionUniqueNo(originalTransaction.getTransactionUniqueNo())
                .cancelAmount(originalTransaction.getAmount())
                .cancelTime(now)
                .remainingBalance(balance.getBalance())
                .build();
    }

    /**
     * Phase 3 (실패): 취소 실패 상태로 마킹
     * - 토스 API 호출 중 불확실한 상태가 된 경우 마킹
     * - 스케줄러가 추후 토스 API 상태를 확인하여 처리
     */
    @Transactional
    public void markAsCancelFailed(Long transactionId) {
        log.warn("[취소-Phase3] 취소 실패 마킹 - 거래ID: {}", transactionId);

        // 실패 상태를 기록하기 위한 별도의 상태 테이블이 필요할 수 있음
        // 현재는 로그만 남기고, 추후 Outbox 패턴과 연동하여 처리
        // TODO: cancel_failed_transactions 테이블에 기록하거나 Outbox 이벤트 발행
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
        if (!originalTransaction.getCustomerId().equals(customerId)) {
            throw new CustomException(ErrorCode.UNAUTHORIZED_ACCESS);
        }

        // 3. 이미 취소된 거래인지 확인
        if (originalTransaction.getRefTransaction() != null) {
            throw new CustomException(ErrorCode.CANCEL_NOT_AVAILABLE);
        }

        // 4. 포인트가 모두 남아있는지 확인 (비관적 락)
        WalletStoreLot lot = walletStoreLotRepository
                .findByOriginChargeTransactionIdWithLock(originalTransaction.getTransactionId())
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
     * Transaction을 CancelListResponseDto로 변환
     */
    private CancelListResponseDto convertToDto(Transaction transaction, String storeName) {
        WalletStoreLot lot = walletStoreLotRepository
                .findByOriginChargeTransactionId(transaction.getTransactionId())
                .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));

        return CancelListResponseDto.builder()
                .transactionUniqueNo(transaction.getTransactionUniqueNo())
                .storeName(storeName)
                .paymentAmount(transaction.getAmount())
                .transactionTime(transaction.getCreatedAt())
                .remainingBalance(lot.getAmountRemaining())
                .build();
    }
}
