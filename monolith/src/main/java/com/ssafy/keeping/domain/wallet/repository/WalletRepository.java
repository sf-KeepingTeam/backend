package com.ssafy.keeping.domain.wallet.repository;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface WalletRepository extends JpaRepository<Wallet, Long> {

  /** 고객과 지갑 타입으로 지갑 조회 */
  Optional<Wallet> findByCustomerAndWalletType(Customer customer, WalletType walletType);

  @Query("""
    select w
    from Wallet w
    where w.group.groupId=:groupId
    """)
  Optional<Wallet> findByGroupId(@Param("groupId") Long groupId);

  /** getBothWalletBalance 전용: 여러 그룹의 지갑을 IN 쿼리 하나로 조회 (groupName 포함) */
  @Query("""
    SELECT w FROM Wallet w
    JOIN FETCH w.group g
    WHERE g.groupId IN :groupIds
      AND w.walletType = 'GROUP'
    """)
  List<Wallet> findGroupWalletsByGroupIdIn(@Param("groupIds") List<Long> groupIds);
}
