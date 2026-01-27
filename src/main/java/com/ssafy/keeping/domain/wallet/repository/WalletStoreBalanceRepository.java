package com.ssafy.keeping.domain.wallet.repository;

import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.model.WalletStoreBalance;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface WalletStoreBalanceRepository extends JpaRepository<WalletStoreBalance, Long> {

    /**
     * 지갑 ID로 모든 잔액 조회
     */
    List<WalletStoreBalance> findByWallet_WalletId(Long walletId);

    /**
     * 가게 ID로 모든 잔액 조회
     */
    List<WalletStoreBalance> findByStoreId(Long storeId);

    /**
     * 지갑 ID와 가게 ID로 잔액 조회
     */
    Optional<WalletStoreBalance> findByWallet_WalletIdAndStoreId(Long walletId, Long storeId);

    @Query("""
        select b
        from WalletStoreBalance b
        join fetch b.wallet
        where b.storeId=:storeId
        and b.wallet.walletId=:walletId
        """)
    Optional<WalletStoreBalance> findByWalletIdAndStoreId(@Param("walletId") Long walletId, @Param("storeId") Long storeId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select b from WalletStoreBalance b
         where b.wallet.walletId = :walletId
           and b.storeId   = :storeId
    """)
    Optional<WalletStoreBalance> lockByWalletIdAndStoreId(@Param("walletId") Long walletId,
                                                          @Param("storeId") Long storeId);
    @Query("""
        select case when count(wb)>0 then true else false end
        from WalletStoreBalance wb
        where wb.storeId = :storeId and wb.balance > 0
    """)
    @Lock(LockModeType.PESSIMISTIC_READ)
    boolean existsPositiveBalanceForStoreWithLock(@Param("storeId") Long storeId);

    /**
     * 잔액이 충분할 때만 balance를 amount만큼 차감
     * - 반환값: 1 = 차감 성공, 0 = 실패(잔액 부족 또는 경합)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE wallet_store_balances
               SET balance = balance - :amount
             WHERE wallet_id = :walletId
               AND store_id = :storeId
               AND balance >= :amount
            """, nativeQuery = true)
    int decrementIfEnough(@Param("walletId") Long walletId, @Param("storeId") Long storeId, @Param("amount") Long amount);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
      select b
      from WalletStoreBalance b
      where b.wallet.walletId = :walletId and b.storeId = :storeId
    """)
    Optional<WalletStoreBalance> findByWalletIdAndStoreIdForUpdate(
            @Param("walletId") Long walletId,
            @Param("storeId") Long storeId
    );

    @Query("""
    select coalesce(sum(b.balance),0)
    from WalletStoreBalance b
    where b.wallet.walletType = :walletType
      and b.wallet.customerId = :customerId
""")
    Optional<Long> sumBalanceByCustomerIdAndType(@Param("customerId") Long customerId,
                                                 @Param("walletType") WalletType walletType);

    @Query(value = """
            SELECT wsb FROM WalletStoreBalance wsb
            JOIN wsb.wallet w
            WHERE w.customerId = :customerId
              AND w.walletType = 'INDIVIDUAL'
              AND wsb.balance > 0
            ORDER BY wsb.updatedAt DESC
            """,
            countQuery = """
            SELECT COUNT(wsb) FROM WalletStoreBalance wsb
            JOIN wsb.wallet w
            WHERE w.customerId = :customerId
              AND w.walletType = 'INDIVIDUAL'
              AND wsb.balance > 0
            """)
    Page<WalletStoreBalance> findPersonalWalletBalancesByCustomerId(@Param("customerId") Long customerId, Pageable pageable);

    @Query(value = """
            SELECT wsb FROM WalletStoreBalance wsb
            JOIN wsb.wallet w
            WHERE w.groupId = :groupId
              AND w.walletType = 'GROUP'
              AND wsb.balance > 0
            ORDER BY wsb.updatedAt DESC
            """,
            countQuery = """
            SELECT COUNT(wsb) FROM WalletStoreBalance wsb
            JOIN wsb.wallet w
            WHERE w.groupId = :groupId
              AND w.walletType = 'GROUP'
              AND wsb.balance > 0
            """)
    Page<WalletStoreBalance> findGroupWalletBalancesByGroupId(@Param("groupId") Long groupId, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select coalesce(sum(b.balance),0)
        from WalletStoreBalance b
        where b.wallet.walletId = :walletId
    """)
    Optional<Long> sumByWalletIdForUpdate(@Param("walletId") Long walletId);

    @Modifying
    @Query("""
        delete from WalletStoreBalance b
        where b.wallet.walletId = :walletId
    """)
    void deleteByWalletId(@Param("walletId") Long walletId);
}