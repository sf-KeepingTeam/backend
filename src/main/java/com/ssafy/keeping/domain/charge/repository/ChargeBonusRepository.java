package com.ssafy.keeping.domain.charge.repository;

import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChargeBonusRepository extends JpaRepository<ChargeBonus, Long> {

    List<ChargeBonus> findByStoreId(Long storeId);

    Optional<ChargeBonus> findByStoreIdAndChargeAmount(Long storeId, Long chargeAmount);

    boolean existsByStoreIdAndChargeAmount(Long storeId, Long chargeAmount);

    @Query("SELECT COUNT(cb) > 0 FROM ChargeBonus cb WHERE cb.storeId = :storeId AND cb.chargeAmount = :chargeAmount AND cb.chargeBonusId != :excludeId")
    boolean existsByStoreIdAndChargeAmountExcludingId(@Param("storeId") Long storeId, @Param("chargeAmount") Long chargeAmount, @Param("excludeId") Long excludeId);
}