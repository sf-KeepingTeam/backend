package com.ssafy.keeping.domain.wallet.repository;

import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletStoreBalanceRepository extends JpaRepository<WalletStoreBalance, Long> {

  /** 지갑과 가게로 잔액 조회 */
  Optional<WalletStoreBalance> findByWalletAndStore(Wallet wallet, Store store);

  @Query(
      """
        select b
        from WalletStoreBalance b
        join fetch b.wallet
        join fetch b.store
        where b.store.storeId=:storeId
        and b.wallet.walletId=:walletId
        """)
  Optional<WalletStoreBalance> findByWalletIdAndStoreId(
      @Param("walletId") Long walletId, @Param("storeId") Long storeId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "3000"))
  @Query(
      """
        select b from WalletStoreBalance b
         where b.wallet.walletId = :walletId
           and b.store.storeId   = :storeId
    """)
  Optional<WalletStoreBalance> lockByWalletIdAndStoreId(
      @Param("walletId") Long walletId, @Param("storeId") Long storeId);

  @Query(
      """
        select case when count(wb)>0 then true else false end
        from WalletStoreBalance wb
        where wb.store.storeId = :storeId and wb.balance > 0
    """)
  @Lock(LockModeType.PESSIMISTIC_READ)
  boolean existsPositiveBalanceForStoreWithLock(@Param("storeId") Long storeId);

  /** 잔액이 충분할 때만 balance를 amount만큼 차감 - 반환값: 1 = 차감 성공, 0 = 실패(잔액 부족 또는 경합) */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      value =
          """
            UPDATE wallet_store_balances
               SET balance = balance - :amount
             WHERE wallet_id = :walletId
               AND store_id = :storeId
               AND balance >= :amount
            """,
      nativeQuery = true)
  int decrementIfEnough(
      @Param("walletId") Long walletId,
      @Param("storeId") Long storeId,
      @Param("amount") Long amount);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select b
      from WalletStoreBalance b
      where b.wallet = :wallet and b.store = :store
    """)
  Optional<WalletStoreBalance> findByWalletAndStoreForUpdate(
      @Param("wallet") Wallet wallet, @Param("store") Store store);

  @Query(
      """
    select coalesce(sum(b.balance),0)
    from WalletStoreBalance b
    where b.wallet.walletType = :walletType
      and b.wallet.customer.customerId = :customerId
""")
  Optional<Long> sumBalanceByCustomerIdAndType(
      @Param("customerId") Long customerId, @Param("walletType") WalletType walletType);

  @Query(
      """
        SELECT wsb FROM WalletStoreBalance wsb
        JOIN FETCH wsb.store s
        JOIN wsb.wallet w
        WHERE w.customer.customerId = :customerId
          AND w.walletType = 'INDIVIDUAL'
          AND wsb.balance > 0
        ORDER BY wsb.updatedAt DESC
        """)
  Slice<WalletStoreBalance> findPersonalWalletBalancesByCustomerId(
      @Param("customerId") Long customerId, Pageable pageable);

  @Query(
      """
        SELECT wsb FROM WalletStoreBalance wsb
        JOIN FETCH wsb.store s
        JOIN wsb.wallet w
        WHERE w.group.groupId = :groupId
          AND w.walletType = 'GROUP'
          AND wsb.balance > 0
        ORDER BY wsb.updatedAt DESC
        """)
  Slice<WalletStoreBalance> findGroupWalletBalancesByGroupId(
      @Param("groupId") Long groupId, Pageable pageable);

  /** getBothWalletBalance 전용: 여러 그룹의 잔액을 IN 쿼리 하나로 조회 */
  @Query(
      """
        SELECT wsb FROM WalletStoreBalance wsb
        JOIN FETCH wsb.store s
        JOIN FETCH wsb.wallet w
        JOIN FETCH w.group g
        WHERE g.groupId IN :groupIds
          AND w.walletType = 'GROUP'
          AND wsb.balance > 0
        ORDER BY g.groupId, wsb.updatedAt DESC
        """)
  List<WalletStoreBalance> findGroupWalletBalancesByGroupIds(
      @Param("groupIds") List<Long> groupIds);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
        select coalesce(sum(b.balance),0)
        from WalletStoreBalance b
        where b.wallet.walletId = :walletId
    """)
  Optional<Long> sumByWalletIdForUpdate(@Param("walletId") Long walletId);

  @Modifying
  @Query(
      """
        delete from WalletStoreBalance b
        where b.wallet.walletId = :walletId
    """)
  void deleteByWalletId(@Param("walletId") Long walletId);
}
