package com.ssafy.keeping.domain.notification.service;

import com.ssafy.keeping.domain.notification.dto.NotificationResponseDto;
import com.ssafy.keeping.domain.notification.entity.Notification;
import com.ssafy.keeping.domain.notification.repository.NotificationRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationQueryService {

    private final NotificationRepository notificationRepository;
    private final CustomerRepository customerRepository;
    private final OwnerRepository ownerRepository;

    /**
     * 고객 알림 읽음 처리
     * @param customerId 고객 ID
     * @param notificationId 알림 ID
     */
    @Transactional
    public void markAsReadForCustomer(Long customerId, Long notificationId) {
        log.info("고객 알림 읽음 처리 요청 - 고객ID: {}, 알림ID: {}", customerId, notificationId);
        
        // 권한 검증 및 알림 조회
        Notification notification = notificationRepository.findByNotificationIdAndCustomerId(notificationId, customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));
        
        // 이미 읽은 알림인지 확인
        if (Boolean.TRUE.equals(notification.getIsRead())) {
            throw new CustomException(ErrorCode.NOTIFICATION_ALREADY_READ);
        }
        
        // 읽음 처리
        notification.markAsRead();
        notificationRepository.save(notification);
        
        log.info("고객 알림 읽음 처리 성공 - 고객ID: {}, 알림ID: {}", customerId, notificationId);
    }

    /**
     * 점주 알림 읽음 처리
     * @param ownerId 점주 ID
     * @param notificationId 알림 ID
     */
    @Transactional
    public void markAsReadForOwner(Long ownerId, Long notificationId) {
        log.info("점주 알림 읽음 처리 요청 - 점주ID: {}, 알림ID: {}", ownerId, notificationId);
        
        // 권한 검증 및 알림 조회
        Notification notification = notificationRepository.findByNotificationIdAndOwnerId(notificationId, ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.NOTIFICATION_NOT_FOUND));
        
        // 이미 읽은 알림인지 확인
        if (Boolean.TRUE.equals(notification.getIsRead())) {
            throw new CustomException(ErrorCode.NOTIFICATION_ALREADY_READ);
        }
        
        // 읽음 처리
        notification.markAsRead();
        notificationRepository.save(notification);
        
        log.info("점주 알림 읽음 처리 성공 - 점주ID: {}, 알림ID: {}", ownerId, notificationId);
    }

    /**
     * 고객 알림 목록 조회
     * @param customerId 고객 ID
     * @param pageable 페이징 정보
     * @return 고객 알림 목록
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponseDto> getNotificationListForCustomer(Long customerId, Pageable pageable) {
        log.info("고객 알림 목록 조회 요청 - 고객ID: {}, 페이지: {}", customerId, pageable.getPageNumber());
        
        // 고객 존재 여부 확인
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));
        
        // 알림 목록 조회
        Page<Notification> notifications = notificationRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        
        log.info("고객 알림 목록 조회 완료 - 고객ID: {}, 총 알림 수: {}, 페이지 알림 수: {}", 
                customerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return notifications.map(NotificationResponseDto::from);
    }

    /**
     * 점주 알림 목록 조회
     * @param ownerId 점주 ID
     * @param pageable 페이징 정보
     * @return 점주 알림 목록
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponseDto> getNotificationListForOwner(Long ownerId, Pageable pageable) {
        log.info("점주 알림 목록 조회 요청 - 점주ID: {}, 페이지: {}", ownerId, pageable.getPageNumber());
        
        // 점주 존재 여부 확인
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.OWNER_NOT_FOUND));
        
        // 알림 목록 조회
        Page<Notification> notifications = notificationRepository.findByOwnerIdOrderByCreatedAtDesc(ownerId, pageable);
        
        log.info("점주 알림 목록 조회 완료 - 점주ID: {}, 총 알림 수: {}, 페이지 알림 수: {}", 
                ownerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return notifications.map(NotificationResponseDto::from);
    }

    /**
     * 고객 읽지 않은 알림 개수 조회
     * @param customerId 고객 ID
     * @return 읽지 않은 알림 개수
     */
    @Transactional(readOnly = true)
    public long getUnreadCountForCustomer(Long customerId) {
        log.info("고객 읽지 않은 알림 개수 조회 요청 - 고객ID: {}", customerId);
        
        // 고객 존재 여부 확인
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));
        
        long unreadCount = notificationRepository.countUnreadByCustomerId(customerId);
        
        log.info("고객 읽지 않은 알림 개수 조회 완료 - 고객ID: {}, 읽지 않은 알림 수: {}", customerId, unreadCount);
        
        return unreadCount;
    }

    /**
     * 점주 읽지 않은 알림 개수 조회
     * @param ownerId 점주 ID
     * @return 읽지 않은 알림 개수
     */
    @Transactional(readOnly = true)
    public long getUnreadCountForOwner(Long ownerId) {
        log.info("점주 읽지 않은 알림 개수 조회 요청 - 점주ID: {}", ownerId);
        
        // 점주 존재 여부 확인
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.OWNER_NOT_FOUND));
        
        long unreadCount = notificationRepository.countUnreadByOwnerId(ownerId);
        
        log.info("점주 읽지 않은 알림 개수 조회 완료 - 점주ID: {}, 읽지 않은 알림 수: {}", ownerId, unreadCount);
        
        return unreadCount;
    }

    /**
     * 고객 읽지 않은 알림 목록 조회
     * @param customerId 고객 ID
     * @param pageable 페이징 정보
     * @return 고객 읽지 않은 알림 목록
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponseDto> getUnreadNotificationListForCustomer(Long customerId, Pageable pageable) {
        log.info("고객 읽지 않은 알림 목록 조회 요청 - 고객ID: {}, 페이지: {}", customerId, pageable.getPageNumber());
        
        // 고객 존재 여부 확인
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));
        
        // 읽지 않은 알림 목록 조회
        Page<Notification> notifications = notificationRepository.findUnreadNotificationsByCustomerIdOrderByCreatedAtDesc(customerId, pageable);
        
        log.info("고객 읽지 않은 알림 목록 조회 완료 - 고객ID: {}, 총 읽지 않은 알림 수: {}, 페이지 알림 수: {}", 
                customerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return notifications.map(NotificationResponseDto::from);
    }

    /**
     * 점주 읽지 않은 알림 목록 조회
     * @param ownerId 점주 ID
     * @param pageable 페이징 정보
     * @return 점주 읽지 않은 알림 목록
     */
    @Transactional(readOnly = true)
    public Page<NotificationResponseDto> getUnreadNotificationListForOwner(Long ownerId, Pageable pageable) {
        log.info("점주 읽지 않은 알림 목록 조회 요청 - 점주ID: {}, 페이지: {}", ownerId, pageable.getPageNumber());
        
        // 점주 존재 여부 확인
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.OWNER_NOT_FOUND));
        
        // 읽지 않은 알림 목록 조회
        Page<Notification> notifications = notificationRepository.findUnreadNotificationsByOwnerIdOrderByCreatedAtDesc(ownerId, pageable);
        
        log.info("점주 읽지 않은 알림 목록 조회 완료 - 점주ID: {}, 총 읽지 않은 알림 수: {}, 페이지 알림 수: {}", 
                ownerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return notifications.map(NotificationResponseDto::from);
    }
}