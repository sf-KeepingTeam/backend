package com.ssafy.keeping.domain.user.customer.repository;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {
    // 아이디로 조회
    Optional<Customer> findByCustomerIdAndDeletedAtIsNull(Long customerId);

    // 소셜 타입과 id 로 조회
    Optional<Customer> findByProviderTypeAndProviderIdAndDeletedAtIsNull(AuthProvider providerType, String providerId);

    // 중복 가입 방지
    boolean existsByPhoneNumberAndDeletedAtIsNull(String phoneNumber);

    // 탈퇴한 사용자 조회(탈퇴 시점 확인용)
    Optional<Customer> findByPhoneNumberAndDeletedAtIsNotNull(String phoneNumber);

    // 전체 조회
    List<Customer> findAllByDeletedAtIsNull();

    // 핸드폰 번호로 고객 찾기
    Optional<Customer> findByPhoneNumberAndDeletedAtIsNull(String phoneNumber);

    Optional<Customer> findByPhoneNumberAndDeletedAtIsNotNullOrderByDeletedAtDesc(String phoneNumber);

    @Modifying
    @Query("UPDATE Customer c SET c.imgUrl = :imgUrl WHERE c.customerId = :customerId")
    int updateImageUrl(@Param("customerId") Long customerId, @Param("imgUrl") String imgUrl);

    @Query("SELECT c.imgUrl FROM Customer c WHERE c.customerId = :customerId")
    Optional<String> findImageUrlByCustomerId(@Param("customerId") Long customerId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE Customer c SET c.name = :name, c.phoneNumber = :phoneNumber WHERE c.customerId = :customerId")
    int updateCustomerProfile(@Param("customerId") Long customerId, @Param("name") String name, @Param("phoneNumber") String phoneNumber);
}