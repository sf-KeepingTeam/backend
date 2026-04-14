package com.ssafy.keeping.qr.domain.intent.repository;

import com.ssafy.keeping.qr.domain.intent.model.PaymentIntentItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PaymentIntentItemRepository extends JpaRepository<PaymentIntentItem, Long> {

    List<PaymentIntentItem> findByIntent_IntentId(Long intentId);
}
