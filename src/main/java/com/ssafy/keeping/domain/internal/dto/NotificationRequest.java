package com.ssafy.keeping.domain.internal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {
    private String targetType;     // CUSTOMER, OWNER (Payment Service 호환)
    private Long targetId;         // (Payment Service 호환)
    private String type;           // NotificationType (Payment Service 호환)
    private String content;

    // Alias getters for backward compatibility
    public String getReceiverType() {
        return targetType;
    }

    public Long getReceiverId() {
        return targetId;
    }

    public String getNotificationType() {
        return type;
    }
}
