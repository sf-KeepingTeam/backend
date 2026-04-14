package com.ssafy.keeping.domain.user.owner.repository;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface OwnerRepository extends JpaRepository<Owner, Long> {
    Optional<Owner> findByProviderTypeAndProviderIdAndDeletedAtIsNull(AuthProvider providerType, String providerId);

    // 아이디로 조회
    Optional<Owner> findByOwnerIdAndDeletedAtIsNull(Long ownerId);

    // 중복 가입 방지
    boolean existsByPhoneNumberAndDeletedAtIsNull(String phoneNumber);

    Optional<Owner> findByPhoneNumberAndDeletedAtIsNotNullOrderByDeletedAtDesc(String phoneNumber);

    @Modifying
    @Query("UPDATE Owner o SET o.imgUrl = :imgUrl WHERE o.ownerId = :ownerId")
    int updateImageUrl(@Param("ownerId") Long ownerId, @Param("imgUrl") String imgUrl);

    @Query("SELECT o.imgUrl FROM Owner o WHERE o.ownerId = :ownerId")
    Optional<String> findImageUrlByOwnerId(@Param("ownerId") Long ownerId);
}