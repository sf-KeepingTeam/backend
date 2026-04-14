package com.ssafy.keeping.domain.group.model;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.group.constant.RequestStatus;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "group_add_requests",
        indexes = {
                @Index(name = "idx_user_group_status_created_at", columnList = "customer_id, group_id, status, created_at"),
                @Index(name = "idx_group_status_created_at",      columnList = "group_id, status, created_at")
        }
)
@EntityListeners(AuditingEntityListener.class)
public class GroupAddRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_add_requests_id")
    private Long groupAddRequestId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer user;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private RequestStatus requestStatus = RequestStatus.PENDING;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void changeStatus(RequestStatus status) {
        if (!Objects.equals(this.requestStatus, status)) this.requestStatus = status;
    }
}