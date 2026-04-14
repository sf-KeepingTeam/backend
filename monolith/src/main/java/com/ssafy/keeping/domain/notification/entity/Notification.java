package com.ssafy.keeping.domain.notification.entity;


import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.owner.model.Owner;
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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Owner owner;

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
        if (customer != null) return "CUSTOMER";
        if (owner != null) return "OWNER";
        return "UNKNOWN";
    }

    public Long getReceiverId() {
        if (customer != null) return customer.getCustomerId();
        if (owner != null) return owner.getOwnerId();
        return null;
    }

    public String getReceiverName() {
        if (customer != null) return customer.getName();
        if (owner != null) return owner.getName();
        return "알 수 없는 사용자";
    }
}