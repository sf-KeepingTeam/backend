package com.ssafy.keeping.domain.notification.repository;

import com.ssafy.keeping.domain.notification.entity.FcmToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FcmTokenRepository extends JpaRepository<FcmToken, Long> {

    /**
     * 특정 고객의 FCM 토큰 목록 조회
     */
    @Query("SELECT f FROM FcmToken f WHERE f.customer.customerId = :customerId")
    List<FcmToken> findByCustomerId(@Param("customerId") Long customerId);

    /**
     * 특정 점주의 FCM 토큰 목록 조회
     */
    @Query("SELECT f FROM FcmToken f WHERE f.owner.ownerId = :ownerId")
    List<FcmToken> findByOwnerId(@Param("ownerId") Long ownerId);

    /**
     * 고객의 특정 토큰 조회
     */
    @Query("SELECT f FROM FcmToken f WHERE f.customer.customerId = :customerId AND f.token = :token")
    Optional<FcmToken> findByCustomerIdAndToken(@Param("customerId") Long customerId, @Param("token") String token);

    /**
     * 점주의 특정 토큰 조회
     */
    @Query("SELECT f FROM FcmToken f WHERE f.owner.ownerId = :ownerId AND f.token = :token")
    Optional<FcmToken> findByOwnerIdAndToken(@Param("ownerId") Long ownerId, @Param("token") String token);

    /**
     * 특정 토큰 삭제
     */
    void deleteByToken(String token);
}