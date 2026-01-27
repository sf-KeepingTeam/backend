package com.ssafy.keeping.domain.favorite.repository;

import com.ssafy.keeping.domain.favorite.model.StoreFavorite;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoreFavoriteRepository extends JpaRepository<StoreFavorite, Long> {

    Optional<StoreFavorite> findByCustomerIdAndStoreId(Long customerId, Long storeId);

    Optional<StoreFavorite> findByCustomerIdAndStoreIdAndActiveTrue(Long customerId, Long storeId);

    Page<StoreFavorite> findByCustomerIdAndActiveTrueOrderByFavoritedAtDesc(Long customerId, Pageable pageable);

    long countByCustomerIdAndActiveTrue(Long customerId);

    long countByStoreIdAndActiveTrue(Long storeId);
}