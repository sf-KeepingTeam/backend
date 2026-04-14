package com.ssafy.keeping.domain.notification.repository;

import com.ssafy.keeping.domain.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, Long> {

    /**
     * 특정 고객의 알림인지 확인 (권한 검증용)
     */
    @Query("SELECT n FROM Notification n WHERE n.notificationId = :notificationId AND n.customer.customerId = :customerId")
    Optional<Notification> findByNotificationIdAndCustomerId(@Param("notificationId") Long notificationId, 
                                                           @Param("customerId") Long customerId);

    /**
     * 특정 점주의 알림인지 확인 (권한 검증용)
     */
    @Query("SELECT n FROM Notification n WHERE n.notificationId = :notificationId AND n.owner.ownerId = :ownerId")
    Optional<Notification> findByNotificationIdAndOwnerId(@Param("notificationId") Long notificationId, 
                                                        @Param("ownerId") Long ownerId);

    /**
     * 특정 고객의 알림 목록 조회 (최신순)
     */
    @Query("SELECT n FROM Notification n WHERE n.customer.customerId = :customerId ORDER BY n.createdAt DESC")
    Page<Notification> findByCustomerIdOrderByCreatedAtDesc(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * 특정 점주의 알림 목록 조회 (최신순)
     */
    @Query("SELECT n FROM Notification n WHERE n.owner.ownerId = :ownerId ORDER BY n.createdAt DESC")
    Page<Notification> findByOwnerIdOrderByCreatedAtDesc(@Param("ownerId") Long ownerId, Pageable pageable);

    /**
     * 특정 고객의 읽지 않은 알림 개수 조회
     */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.customer.customerId = :customerId AND n.isRead = false")
    long countUnreadByCustomerId(@Param("customerId") Long customerId);

    /**
     * 특정 점주의 읽지 않은 알림 개수 조회
     */
    @Query("SELECT COUNT(n) FROM Notification n WHERE n.owner.ownerId = :ownerId AND n.isRead = false")
    long countUnreadByOwnerId(@Param("ownerId") Long ownerId);


    /**
     * 특정 고객의 읽지 않은 알림 목록 조회 (페이징, 최신순)
     */
    @Query("SELECT n FROM Notification n WHERE n.customer.customerId = :customerId AND n.isRead = false ORDER BY n.createdAt DESC")
    Page<Notification> findUnreadNotificationsByCustomerIdOrderByCreatedAtDesc(@Param("customerId") Long customerId, Pageable pageable);

    /**
     * 특정 점주의 읽지 않은 알림 목록 조회 (페이징, 최신순)
     */
    @Query("SELECT n FROM Notification n WHERE n.owner.ownerId = :ownerId AND n.isRead = false ORDER BY n.createdAt DESC")
    Page<Notification> findUnreadNotificationsByOwnerIdOrderByCreatedAtDesc(@Param("ownerId") Long ownerId, Pageable pageable);
}