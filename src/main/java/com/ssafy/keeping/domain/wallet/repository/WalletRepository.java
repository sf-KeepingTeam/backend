package com.ssafy.keeping.domain.wallet.repository;

import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

    /**
     * 고객 ID와 지갑 타입으로 지갑 조회
     */
    Optional<Wallet> findByCustomerIdAndWalletType(Long customerId, WalletType walletType);

    /**
     * 고객 ID로 지갑 조회
     */
    Optional<Wallet> findByCustomerId(Long customerId);

    /**
     * 그룹 ID로 지갑 조회
     */
    Optional<Wallet> findByGroupId(Long groupId);
}