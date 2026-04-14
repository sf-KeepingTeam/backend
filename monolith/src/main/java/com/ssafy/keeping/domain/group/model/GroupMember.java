package com.ssafy.keeping.domain.group.model;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(
        name = "group_members",
        uniqueConstraints = @UniqueConstraint(name = "uq_group_member", columnNames = {"group_id","customer_id"}),
        indexes = {
                @Index(name = "idx_customer_group", columnList = "customer_id, group_id"),
                @Index(name = "idx_group_leader",  columnList = "group_id, leader")
        }
)
@EntityListeners(AuditingEntityListener.class)
public class GroupMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_member_id")
    private Long groupMemberId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id", nullable = false)
    private Group group;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer user;

    @Column(name = "leader", nullable = false)
    private boolean leader;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public boolean changeLeader(boolean leader) {
        if (this.leader == leader) return false;
        this.leader = leader;
        return true;
    }
}