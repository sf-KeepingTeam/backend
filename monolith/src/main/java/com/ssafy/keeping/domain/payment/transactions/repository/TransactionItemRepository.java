package com.ssafy.keeping.domain.payment.transactions.repository;

import com.ssafy.keeping.domain.payment.transactions.model.TransactionItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionItemRepository extends JpaRepository<TransactionItem, Long> {
}
