package com.ssafy.keeping.domain.favorite.repository;

import com.ssafy.keeping.domain.favorite.model.StoreFavorite;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.store.model.Store;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoreFavoriteRepository extends JpaRepository<StoreFavorite, Long> {

    Optional<StoreFavorite> findByCustomerAndStore(Customer customer, Store store);

    Optional<StoreFavorite> findByCustomerAndStoreAndActiveTrue(Customer customer, Store store);

    Page<StoreFavorite> findByCustomerAndActiveTrueOrderByFavoritedAtDesc(Customer customer, Pageable pageable);

    long countByCustomerAndActiveTrue(Customer customer);

    long countByStoreAndActiveTrue(Store store);
}