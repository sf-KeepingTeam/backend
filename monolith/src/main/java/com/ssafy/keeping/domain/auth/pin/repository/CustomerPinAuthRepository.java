package com.ssafy.keeping.domain.auth.pin.repository;

import com.ssafy.keeping.domain.auth.pin.model.CustomerPinAuth;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerPinAuthRepository extends JpaRepository<CustomerPinAuth, Long> {
    // 기본 CRUD
}
