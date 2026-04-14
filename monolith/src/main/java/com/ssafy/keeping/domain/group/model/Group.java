package com.ssafy.keeping.domain.group.model;

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
        name = "`groups`",
        uniqueConstraints = @UniqueConstraint(name = "uq_groups_code", columnNames = "group_code")
)
@EntityListeners(AuditingEntityListener.class)
public class Group {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "group_name", nullable = false, length = 100)
    private String groupName;

    @Column(name = "group_code", nullable = false, length = 100)
    private String groupCode;

    @Column(name = "group_description", nullable = false, length = 150)
    private String groupDescription;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public void editGroup(String groupName, String groupDescription) {
        if (!Objects.equals(groupName, this.groupName))
            this.groupName = groupName;

        if (!Objects.equals(groupDescription, this.groupDescription))
            this.groupDescription = groupDescription;
    }
}
