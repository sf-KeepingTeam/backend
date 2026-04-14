package com.ssafy.keeping.domain.charge.repository;

import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import com.ssafy.keeping.domain.store.model.Store;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChargeBonusRepository extends JpaRepository<ChargeBonus, Long> {

    List<ChargeBonus> findByStore(Store store);

    Optional<ChargeBonus> findByStoreAndChargeAmount(Store store, Long chargeAmount);

    boolean existsByStoreAndChargeAmount(Store store, Long chargeAmount);

    @Query("SELECT COUNT(cb) > 0 FROM ChargeBonus cb WHERE cb.store = :store AND cb.chargeAmount = :chargeAmount AND cb.chargeBonusId != :excludeId")
    boolean existsByStoreAndChargeAmountExcludingId(@Param("store") Store store, @Param("chargeAmount") Long chargeAmount, @Param("excludeId") Long excludeId);
}