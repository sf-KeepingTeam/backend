package com.ssafy.keeping.domain.notification.controller;

import com.ssafy.keeping.domain.notification.dto.NotificationResponseDto;
import com.ssafy.keeping.domain.notification.service.NotificationService;
import com.ssafy.keeping.domain.notification.service.NotificationQueryService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Slf4j
@Validated
public class NotificationController {

    private final NotificationService notificationService;
    private final NotificationQueryService notificationQueryService;

    /**
     * 고객용 SSE 구독
     * 
     * @param customerId 고객 ID
     * @param lastEventId 마지막으로 받은 이벤트 ID (재연결용)
     * @return SseEmitter
     * 로그인 시에 이걸 불러서 구독하도록 할 것임
     */
    @GetMapping(value = "/subscribe/customer/{customerId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeCustomer(@PathVariable Long customerId,
                                       @RequestHeader(value = "Last-Event-ID", required = false, defaultValue = "") String lastEventId,
                                       @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        log.info("고객 SSE 구독 요청 - 고객ID: {}, Last-Event-ID: {}, Authorization: {}",
                customerId, lastEventId, authorizationHeader != null ? "있음" : "없음");

        // Authorization 헤더에서 AccessToken 추출
        String accessToken = extractAccessToken(authorizationHeader);

        return notificationService.subscribe("customer", customerId, lastEventId, accessToken);
    }

    /**
     * 점주용 SSE 구독
     * 
     * @param ownerId 점주 ID
     * @param lastEventId 마지막으로 받은 이벤트 ID (재연결용)
     * @return SseEmitter
     * 로그인 시에 이걸 불러서 구독하도록 할 것임
     */
    @GetMapping(value = "/subscribe/owner/{ownerId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribeOwner(@PathVariable Long ownerId,
                                    @RequestHeader(value = "Last-Event-ID", required = false, defaultValue = "") String lastEventId,
                                    @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        log.info("점주 SSE 구독 요청 - 점주ID: {}, Last-Event-ID: {}, Authorization: {}",
                ownerId, lastEventId, authorizationHeader != null ? "있음" : "없음");

        // Authorization 헤더에서 AccessToken 추출
        String accessToken = extractAccessToken(authorizationHeader);

        return notificationService.subscribe("owner", ownerId, lastEventId, accessToken);
    }

    /**
     * 고객 알림 읽음 처리
     * 
     * @param customerId 고객 ID
     * @param notificationId 알림 ID
     * @return 읽음 처리 결과
     */
    @PutMapping("/customer/{customerId}/{notificationId}/read")
    public ResponseEntity<ApiResponse<String>> markAsReadForCustomer(
            @PathVariable @Positive(message = "고객 ID는 양수여야 합니다.") Long customerId,
            @PathVariable @Positive(message = "알림 ID는 양수여야 합니다.") Long notificationId) {
        
        log.info("고객 알림 읽음 처리 요청 - 고객ID: {}, 알림ID: {}", customerId, notificationId);
        
        notificationQueryService.markAsReadForCustomer(customerId, notificationId);
        
        log.info("고객 알림 읽음 처리 성공 - 고객ID: {}, 알림ID: {}", customerId, notificationId);
        return ResponseEntity.ok(
            ApiResponse.success("알림이 읽음 처리되었습니다.", HttpStatus.OK.value(), "success")
        );
    }

    /**
     * 점주 알림 읽음 처리
     * 
     * @param ownerId 점주 ID
     * @param notificationId 알림 ID
     * @return 읽음 처리 결과
     */
    @PutMapping("/owner/{ownerId}/{notificationId}/read")
    public ResponseEntity<ApiResponse<String>> markAsReadForOwner(
            @PathVariable @Positive(message = "점주 ID는 양수여야 합니다.") Long ownerId,
            @PathVariable @Positive(message = "알림 ID는 양수여야 합니다.") Long notificationId) {
        
        log.info("점주 알림 읽음 처리 요청 - 점주ID: {}, 알림ID: {}", ownerId, notificationId);
        
        notificationQueryService.markAsReadForOwner(ownerId, notificationId);
        
        log.info("점주 알림 읽음 처리 성공 - 점주ID: {}, 알림ID: {}", ownerId, notificationId);
        return ResponseEntity.ok(
            ApiResponse.success("알림이 읽음 처리되었습니다.", HttpStatus.OK.value(), "success")
        );
    }

    /**
     * 고객 알림 목록 조회
     * 
     * @param customerId 고객 ID
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     * @return 고객 알림 목록 (페이징)
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<ApiResponse<Page<NotificationResponseDto>>> getNotificationListForCustomer(
            @PathVariable @Positive(message = "고객 ID는 양수여야 합니다.") Long customerId,
            @RequestParam(defaultValue = "0") @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20") @Positive(message = "페이지 크기는 양수여야 합니다.") int size) {
        
        log.info("고객 알림 목록 조회 요청 - 고객ID: {}, 페이지: {}, 크기: {}", customerId, page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<NotificationResponseDto> notifications = notificationQueryService.getNotificationListForCustomer(customerId, pageable);
        
        log.info("고객 알림 목록 조회 성공 - 고객ID: {}, 총 개수: {}, 현재 페이지 개수: {}", 
                customerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return ResponseEntity.ok(
            ApiResponse.success(
                "고객 알림 목록 조회 완료", 
                HttpStatus.OK.value(), 
                notifications
            )
        );
    }

    /**
     * 점주 알림 목록 조회
     * 
     * @param ownerId 점주 ID
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     * @return 점주 알림 목록 (페이징)
     */
    @GetMapping("/owner/{ownerId}")
    public ResponseEntity<ApiResponse<Page<NotificationResponseDto>>> getNotificationListForOwner(
            @PathVariable @Positive(message = "점주 ID는 양수여야 합니다.") Long ownerId,
            @RequestParam(defaultValue = "0") @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20") @Positive(message = "페이지 크기는 양수여야 합니다.") int size) {
        
        log.info("점주 알림 목록 조회 요청 - 점주ID: {}, 페이지: {}, 크기: {}", ownerId, page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<NotificationResponseDto> notifications = notificationQueryService.getNotificationListForOwner(ownerId, pageable);
        
        log.info("점주 알림 목록 조회 성공 - 점주ID: {}, 총 개수: {}, 현재 페이지 개수: {}", 
                ownerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return ResponseEntity.ok(
            ApiResponse.success(
                "점주 알림 목록 조회 완료", 
                HttpStatus.OK.value(), 
                notifications
            )
        );
    }

    /**
     * 고객 읽지 않은 알림 개수 조회
     * 
     * @param customerId 고객 ID
     * @return 읽지 않은 알림 개수
     */
    @GetMapping("/customer/{customerId}/unread-count")
    public ResponseEntity<ApiResponse<Long>> getUnreadCountForCustomer(
            @PathVariable @Positive(message = "고객 ID는 양수여야 합니다.") Long customerId) {
        
        log.info("고객 읽지 않은 알림 개수 조회 요청 - 고객ID: {}", customerId);
        
        long unreadCount = notificationQueryService.getUnreadCountForCustomer(customerId);
        
        log.info("고객 읽지 않은 알림 개수 조회 성공 - 고객ID: {}, 읽지 않은 개수: {}", customerId, unreadCount);
        
        return ResponseEntity.ok(
            ApiResponse.success(
                "읽지 않은 알림 개수 조회 완료", 
                HttpStatus.OK.value(), 
                unreadCount
            )
        );
    }

    /**
     * 점주 읽지 않은 알림 개수 조회
     * 
     * @param ownerId 점주 ID
     * @return 읽지 않은 알림 개수
     */
    @GetMapping("/owner/{ownerId}/unread-count")
    public ResponseEntity<ApiResponse<Long>> getUnreadCountForOwner(
            @PathVariable @Positive(message = "점주 ID는 양수여야 합니다.") Long ownerId) {
        
        log.info("점주 읽지 않은 알림 개수 조회 요청 - 점주ID: {}", ownerId);
        
        long unreadCount = notificationQueryService.getUnreadCountForOwner(ownerId);
        
        log.info("점주 읽지 않은 알림 개수 조회 성공 - 점주ID: {}, 읽지 않은 개수: {}", ownerId, unreadCount);
        
        return ResponseEntity.ok(
            ApiResponse.success(
                "읽지 않은 알림 개수 조회 완료", 
                HttpStatus.OK.value(), 
                unreadCount
            )
        );
    }

    /**
     * 고객 읽지 않은 알림 목록 조회
     * 
     * @param customerId 고객 ID
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     * @return 고객 읽지 않은 알림 목록 (페이징)
     */
    @GetMapping("/customer/{customerId}/unread")
    public ResponseEntity<ApiResponse<Page<NotificationResponseDto>>> getUnreadNotificationListForCustomer(
            @PathVariable @Positive(message = "고객 ID는 양수여야 합니다.") Long customerId,
            @RequestParam(defaultValue = "0") @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20") @Positive(message = "페이지 크기는 양수여야 합니다.") int size) {
        
        log.info("고객 읽지 않은 알림 목록 조회 요청 - 고객ID: {}, 페이지: {}, 크기: {}", customerId, page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<NotificationResponseDto> notifications = notificationQueryService.getUnreadNotificationListForCustomer(customerId, pageable);
        
        log.info("고객 읽지 않은 알림 목록 조회 성공 - 고객ID: {}, 총 개수: {}, 현재 페이지 개수: {}", 
                customerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return ResponseEntity.ok(
            ApiResponse.success(
                "고객 읽지 않은 알림 목록 조회 완료", 
                HttpStatus.OK.value(), 
                notifications
            )
        );
    }

    /**
     * 점주 읽지 않은 알림 목록 조회
     * 
     * @param ownerId 점주 ID
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기
     * @return 점주 읽지 않은 알림 목록 (페이징)
     */
    @GetMapping("/owner/{ownerId}/unread")
    public ResponseEntity<ApiResponse<Page<NotificationResponseDto>>> getUnreadNotificationListForOwner(
            @PathVariable @Positive(message = "점주 ID는 양수여야 합니다.") Long ownerId,
            @RequestParam(defaultValue = "0") @PositiveOrZero(message = "페이지 번호는 0 이상이어야 합니다.") int page,
            @RequestParam(defaultValue = "20") @Positive(message = "페이지 크기는 양수여야 합니다.") int size) {
        
        log.info("점주 읽지 않은 알림 목록 조회 요청 - 점주ID: {}, 페이지: {}, 크기: {}", ownerId, page, size);
        
        Pageable pageable = PageRequest.of(page, size);
        Page<NotificationResponseDto> notifications = notificationQueryService.getUnreadNotificationListForOwner(ownerId, pageable);
        
        log.info("점주 읽지 않은 알림 목록 조회 성공 - 점주ID: {}, 총 개수: {}, 현재 페이지 개수: {}", 
                ownerId, notifications.getTotalElements(), notifications.getNumberOfElements());
        
        return ResponseEntity.ok(
            ApiResponse.success(
                "점주 읽지 않은 알림 목록 조회 완료", 
                HttpStatus.OK.value(), 
                notifications
            )
        );
    }

    /**
     * Authorization 헤더에서 AccessToken 추출
     * @param authorizationHeader Authorization 헤더 값
     * @return AccessToken 또는 null
     */
    private String extractAccessToken(String authorizationHeader) {
        if (authorizationHeader != null && authorizationHeader.startsWith("Bearer ")) {
            return authorizationHeader.substring(7);
        }
        return null;
    }
}