package com.ssafy.keeping.domain.wallet.repository;

import com.ssafy.keeping.domain.wallet.model.WalletLotMove;
import io.lettuce.core.dynamic.annotation.Param;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

import java.util.List;

public interface WalletLotMoveRepository extends JpaRepository<WalletLotMove, Long> {

    List<WalletLotMove> findByTransaction_TransactionId(Long transactionId);

    List<WalletLotMove> findByLot_LotId(Long lotId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({
            @QueryHint(name = "jakarta.persistence.lock.timeout", value = "5000"),
            @QueryHint(name = "jakarta.persistence.lock.scope", value = "EXTENDED")
    })
    @Query("""
        select m
        from WalletLotMove m
        join fetch m.lot l
        where m.transaction.transactionId = :txId
    """)
    List<WalletLotMove> findAllByTransactionIdWithLotLock(@Param("txId") Long txId);
}
