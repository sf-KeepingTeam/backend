package com.ssafy.keeping.domain.group.model;

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

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    /**
     * 고객 이름 스냅샷 (Pattern 3: 데이터 복제)
     * MSA 전환 시 CustomerRepository 의존성 제거를 위해 가입 시점에 저장
     * 고객 이름 변경 시 CustomerNameChangedEvent로 동기화
     */
    @Column(name = "customer_name_snapshot", length = 50)
    private String customerNameSnapshot;

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