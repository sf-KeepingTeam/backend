package com.ssafy.keeping.domain.notification.dto;

import com.ssafy.keeping.domain.notification.entity.Notification;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import lombok.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class NotificationResponseDto {

    private Long notificationId;
    private String content;
    private Boolean isRead;
    private NotificationType notificationType;
    private String receiverType; // "CUSTOMER" 또는 "OWNER"
    private Long receiverId;
    private String receiverName;
    private String createdAt;

    // Notification 엔티티로부터 DTO 생성
    public static NotificationResponseDto from(Notification notification) {
        return NotificationResponseDto.builder()
                .notificationId(notification.getNotificationId())
                .content(notification.getContent())
                .isRead(notification.getIsRead())
                .notificationType(notification.getNotificationType())
                .receiverType(notification.getReceiverType())
                .receiverId(notification.getReceiverId())
                .receiverName(notification.getReceiverName())
                .createdAt(notification.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .build();
    }

    // SSE 이벤트용 간단한 생성자
    public static NotificationResponseDto forSSE(String content, NotificationType type,
                                                String receiverType, Long receiverId) {
        return NotificationResponseDto.builder()
                .content(content)
                .isRead(false)
                .notificationType(type)
                .receiverType(receiverType)
                .receiverId(receiverId)
                .createdAt(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                .build();
    }
}