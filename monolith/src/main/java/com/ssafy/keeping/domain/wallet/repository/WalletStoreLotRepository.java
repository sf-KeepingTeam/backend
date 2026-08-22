package com.ssafy.keeping.domain.wallet.repository;

import com.ssafy.keeping.domain.payment.transactions.model.Transaction;
import com.ssafy.keeping.domain.wallet.constant.LotSourceType;
import com.ssafy.keeping.domain.wallet.model.WalletStoreLot;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletStoreLotRepository extends JpaRepository<WalletStoreLot, Long> {

  /** 만료 배치 1단계: 락 없이 미정산 만료 lot의 (walletId, storeId) 후보 쌍을 뽑는다. */
  @Query(
      value =
          """
            SELECT DISTINCT l.wallet_id, l.store_id
              FROM wallet_store_lot l
             WHERE l.lot_status = 'ACTIVE'
               AND l.expired_at <= :now
               AND l.expired_settled_at IS NULL
             LIMIT :maxGroups
            """,
      nativeQuery = true)
  List<Object[]> findExpiryCandidatePairs(
      @Param("now") LocalDateTime now, @Param("maxGroups") int maxGroups);

  /** 만료 배치 2단계: 특정 (walletId, storeId)의 미정산 만료 lot을 FOR UPDATE SKIP LOCKED로 잠근다. */
  @Query(
      value =
          """
            SELECT l.*
              FROM wallet_store_lot l
             WHERE l.wallet_id = :walletId
               AND l.store_id  = :storeId
               AND l.lot_status = 'ACTIVE'
               AND l.expired_at <= :now
               AND l.expired_settled_at IS NULL
               FOR UPDATE SKIP LOCKED
            """,
      nativeQuery = true)
  List<WalletStoreLot> lockUnsettledExpiredLots(
      @Param("walletId") Long walletId,
      @Param("storeId") Long storeId,
      @Param("now") LocalDateTime now);

  /** 대사용: ACTIVE lot의 amountRemaining 합계 (walletId, storeId 기준). */
  @Query(
      """
        SELECT COALESCE(SUM(l.amountRemaining), 0)
          FROM WalletStoreLot l
         WHERE l.wallet.walletId = :walletId
           AND l.store.storeId   = :storeId
           AND l.lotStatus = com.ssafy.keeping.domain.wallet.constant.LotStatus.ACTIVE
      """)
  long sumActiveLotRemaining(
      @Param("walletId") Long walletId, @Param("storeId") Long storeId);


  Optional<WalletStoreLot> findByOriginChargeTransaction(Transaction originChargeTransaction);

  /** 결제 취소 시 정합성 보장을 위한 비관적 락 SELECT ... FOR UPDATE로 해당 로트를 독점적으로 잠금 */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT l FROM WalletStoreLot l WHERE l.originChargeTransaction = :transaction")
  Optional<WalletStoreLot> findByOriginChargeTransactionWithLock(
      @Param("transaction") Transaction transaction);

  // 개인 LOT 소진용: FIFO + 행잠금
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
       select l from WalletStoreLot l
       where l.wallet.walletId = :walletId
         and l.store.storeId  = :storeId
       order by l.acquiredAt asc
    """)
  List<WalletStoreLot> lockAllByWalletIdAndStoreIdOrderByAcquiredAt(
      @Param("walletId") Long walletId, @Param("storeId") Long storeId);

  // 그룹 수신 LOT 누적용: 지갑/매장/원천Tx/타입으로 단일 조회
  @Query(
      """
       select l from WalletStoreLot l
       where l.wallet.walletId = :walletId
         and l.store.storeId  = :storeId
         and l.originChargeTransaction.transactionId = :originTxId
         and l.sourceType = :sourceType
    """)
  Optional<WalletStoreLot> findByWalletIdAndStoreIdAndOriginChargeTxIdAndSourceType(
      @Param("walletId") Long walletId,
      @Param("storeId") Long storeId,
      @Param("originTxId") Long originTxId,
      @Param("sourceType") LotSourceType type);

  /** 사용 가능한 로트 목록(FIFO) - ACTIVE && expired_at > :now - 획득시각 오름차순 + lot_id 오름차순 (안정적 정렬) */
  @Query(
      """
        select l from WalletStoreLot l
        where l.wallet.walletId = :walletId
          and l.store.storeId   = :storeId
          and l.lotStatus = com.ssafy.keeping.domain.wallet.constant.LotStatus.ACTIVE
          and l.expiredAt > :now
          and l.amountRemaining > 0
        order by l.acquiredAt asc, l.lotId asc
    """)
  List<WalletStoreLot> findSpendableLots(
      @Param("walletId") Long walletId,
      @Param("storeId") Long storeId,
      @Param("now") LocalDateTime now);

  /** 로트에서 use 만큼만 조건부 차감 (경합 안전) - 영향행 1: 차감 성공 - 영향행 0: 실패(경합/조건 불일치) → 다음 로트로 진행 */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
            UPDATE wallet_store_lot
               SET amount_remaining = amount_remaining - :use
             WHERE lot_id      = :lotId
               AND lot_status  = 'ACTIVE'
               AND expired_at  > :now
               AND amount_remaining >= :use
            """,
      nativeQuery = true)
  int decrementLotIfEnough(
      @Param("lotId") Long lotId, @Param("use") Long use, @Param("now") LocalDateTime now);

  @Query(
      """
    select l from WalletStoreLot l
    where l.wallet.walletId = :walletId
      and l.lotStatus = 'ACTIVE'
      and l.amountRemaining > 0
      and l.contributorWallet.customer.customerId = :customerId
    order by l.acquiredAt asc
    """)
  List<WalletStoreLot> findActiveByWalletIdAndContributorCustomerId(
      @Param("walletId") Long walletId, @Param("customerId") Long customerId);

  @Query(
      """
           select coalesce(sum(l.amountRemaining), 0)
           from WalletStoreLot l
           where l.wallet.walletId = :groupWalletId
             and l.contributorWallet.walletId = :memberWalletId
             and l.lotStatus = com.ssafy.keeping.domain.wallet.constant.LotStatus.ACTIVE
             and l.amountRemaining > 0
             and (l.expiredAt is null or l.expiredAt > CURRENT_TIMESTAMP)
           """)
  long sumAvailablePoints(
      @Param("groupWalletId") Long groupWalletId, @Param("memberWalletId") Long memberWalletId);

  @Query(
      """
    select case when count(l) > 0 then true else false end
    from WalletStoreLot l
    where l.wallet.walletId = :walletId
    and l.lotStatus = 'ACTIVE'
    and l.amountRemaining > 0
    """)
  boolean existsActiveLotByWalletId(@Param("walletId") Long walletId);

  @Modifying
  @Query("""
    delete from WalletStoreLot l
    where l.wallet.walletId = :walletId
    """)
  void deleteByWalletId(@Param("walletId") Long walletId);

  @Query(
      """
    select l from WalletStoreLot l
    join l.wallet w
    join l.store s
    join l.contributorWallet cw
    where w.walletId = :groupWalletId
      and s.storeId = :storeId
      and cw.customer.customerId = :customerId
      and l.sourceType = 'TRANSFER_IN'
      and l.lotStatus = 'ACTIVE'
      and l.amountRemaining > 0
      and (l.expiredAt is null or l.expiredAt > :now)
    """)
  List<WalletStoreLot> findReclaimableByStore(
      @Param("groupWalletId") Long groupWalletId,
      @Param("storeId") Long storeId,
      @Param("customerId") Long customerId,
      @Param("now") LocalDateTime now);
}
