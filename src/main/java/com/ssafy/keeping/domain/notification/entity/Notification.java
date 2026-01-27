package com.ssafy.keeping.domain.notification.entity;


import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_id")
    private Long notificationId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "owner_id")
    private Long ownerId;

    @Column(name = "content", nullable = false, length = 500)
    private String content;
    // 알림 메시지 내용 (사용자가 실제로 보는 텍스트)


    @Column(name = "is_read", nullable = false)
    @Builder.Default
    private Boolean isRead = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "notification_type", nullable = false, length = 50)
    private NotificationType notificationType;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    // 읽음 처리
    public void markAsRead() {
        this.isRead = true;
    }

    // 수신자 정보 조회 (Customer 또는 Owner 중 하나)
    public String getReceiverType() {
        if (customerId != null) return "CUSTOMER";
        if (ownerId != null) return "OWNER";
        return "UNKNOWN";
    }

    public Long getReceiverId() {
        if (customerId != null) return customerId;
        if (ownerId != null) return ownerId;
        return null;
    }
}