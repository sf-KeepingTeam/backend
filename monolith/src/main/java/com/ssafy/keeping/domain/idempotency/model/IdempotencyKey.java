package com.ssafy.keeping.domain.idempotency.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.ssafy.keeping.domain.idempotency.constant.IdemActorType;
import com.ssafy.keeping.domain.idempotency.constant.IdemStatus;
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
                @UniqueConstraint(name="uk_idem_scope",
                        columnNames = {"actor_type", "actor_id", "path", "key_uuid"})
        },
        indexes = {
                @Index(name="idx_idem_created", columnList = "created_at")
        }
)
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="key_uuid", nullable = false, columnDefinition = "BINARY(16)")
    private UUID keyUuid; // 헤더 Idempotency-Key

    @Enumerated(EnumType.STRING)
    @Column(name="actor_type", nullable = false, length = 16)
    private IdemActorType actorType; // MERCHANT/CUSTOMER/SYSTEM

    @Column(name="actor_id", nullable = false)
    private Long actorId;

    // TODO: ENUM으로 변경 고려
    @Column(name="method", nullable = false, length = 10)
    private String method; // POST, PUT...

    @Column(name="path", nullable = false, length = 255)
    private String path;   // API 요청 URL 경로

    @Column(name="body_hash", nullable = false, columnDefinition = "VARBINARY(32)")
    private byte[] bodyHash; // SHA-256(정규화 바디)

    @Enumerated(EnumType.STRING)
    @Column(name="status", nullable = false, length = 16)
    private IdemStatus status; // IN_PROGRESS/DONE

    @Column(name="http_status")
    private Integer httpStatus; // 최초 응답 HTTP 코드

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_json", columnDefinition = "json") // MySQL JSON 가능
    private JsonNode responseJson; // 최초 응답 원문

    @Column(name = "intent_public_id", columnDefinition = "BINARY(16)")
    private UUID intentPublicId;

    @Column(name="created_at", nullable = false, columnDefinition = "DATETIME(3)")
    private LocalDateTime createdAt;

    @PrePersist void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = IdemStatus.IN_PROGRESS;
    }
}