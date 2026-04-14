package com.ssafy.keeping.qr.domain.idempotency.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.qr.domain.idempotency.constant.IdemStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "idempotency_keys",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_idem_scope",
                        columnNames = {"actor_type", "actor_id", "path", "key_uuid"})
        },
        indexes = {
                @Index(name = "idx_idem_created", columnList = "created_at")
        }
)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_uuid", nullable = false, columnDefinition = "BINARY(16)")
    private UUID keyUuid;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 16)
    private IdemActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private Long actorId;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 255)
    private String path;

    @Column(name = "body_hash", nullable = false, columnDefinition = "VARBINARY(32)")
    private byte[] bodyHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private IdemStatus status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", columnDefinition = "json")
    private JsonNode responseJson;

    @Column(name = "intent_public_id", columnDefinition = "BINARY(16)")
    private UUID intentPublicId;

    @Column(name = "created_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = IdemStatus.IN_PROGRESS;
    }
}
