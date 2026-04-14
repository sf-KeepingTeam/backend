package com.ssafy.keeping.domain.notification.service;

import com.ssafy.keeping.domain.auth.token.AccessTokenService;
import com.ssafy.keeping.domain.notification.dto.NotificationResponseDto;
import com.ssafy.keeping.domain.notification.entity.Notification;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.repository.EmitterRepository;
import com.ssafy.keeping.domain.notification.repository.NotificationRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import org.springframework.data.redis.core.StringRedisTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final EmitterRepository emitterRepository;
    private final NotificationRepository notificationRepository;
    private final CustomerRepository customerRepository;
    private final OwnerRepository ownerRepository;
    private final FcmService fcmService;
    private final AccessTokenService accessTokenService;
    private final StringRedisTemplate redisTemplate;

    private static final Long DEFAULT_TIMEOUT = 60L * 1000 * 60; // 60분

    //   - 알림 전송 실패가 핵심 비즈니스를 방해하면 안됨
    //  - 결제 취소는 성공했는데 알림 때문에 전체가 실패하면 더 큰 문제
    //  - 사용자 경험상 알림 없어도 기능은 동작해야 함

    /**
     * 클라이언트가 구독을 위해 호출하는 메서드
     * @param receiverType "customer" 또는 "owner"
     * @param receiverId 사용자 ID
     * @param lastEventId 마지막으로 받은 이벤트 ID (재연결용)
     * @param accessToken AccessToken (토큰 기반 재전송을 위함, null 가능)
     * @return SseEmitter
     */
    // "구독"은 실시간 알림을 받기 위해 맨 처음 단 한 번, 서버와 클라이언트(브라우저) 사이에 '알림 전용 통신선'을 개통하는 행위입니다.
    public SseEmitter subscribe(String receiverType, Long receiverId, String lastEventId, String accessToken) {
        String emitterId = makeTimeIncludeId(receiverType, receiverId);

        SseEmitter sseEmitter = emitterRepository.save(emitterId, new SseEmitter(DEFAULT_TIMEOUT));
        
        log.info("SSE 구독 시작 - {}:{}, EmitterID: {}", receiverType, receiverId, emitterId);
        // Emitter 완료/타임아웃/에러 시 정리 작업
        sseEmitter.onCompletion(() -> {
            log.info("SSE 연결 완료 - EmitterID: {}", emitterId);
            emitterRepository.deleteById(emitterId);
        });
        
        sseEmitter.onTimeout(() -> {
            log.info("SSE 연결 타임아웃 - EmitterID: {}", emitterId);
            emitterRepository.deleteById(emitterId);
        });
        
        sseEmitter.onError((e) -> {
            log.error("SSE 연결 에러 - EmitterID: {}, Error: {}", emitterId, e.getMessage());
            emitterRepository.deleteById(emitterId);
        });

        // 503 에러 방지를 위한 더미 이벤트 전송
        try {
            String eventId = makeTimeIncludeId(receiverType, receiverId);
            boolean connectionSuccess = sendNotification(sseEmitter, eventId, emitterId, 
                "SSE 연결 성공 [" + receiverType + ":" + receiverId + "]");
            
            if (!connectionSuccess) {
                log.warn("초기 연결 이벤트 전송 실패 - EmitterID: {}", emitterId);
            }

            // 토큰 기반 유실 데이터 재전송 처리
            if (accessToken != null && !accessToken.trim().isEmpty()) {
                // AccessToken이 있는 경우 - 네트워크 재연결 시나리오
                log.info("AccessToken 기반 재전송 시도 - {}:{}", receiverType, receiverId);
                sendLostDataWithTokenFilter(accessToken, receiverType, receiverId, emitterId, sseEmitter);
            } else {
                // AccessToken이 없는 경우 - 로그아웃 상태로 간주하여 재전송 차단
                log.info("AccessToken 없음 - 로그아웃 상태로 간주하여 재전송 차단 - {}:{}", receiverType, receiverId);
            }

        } catch (Exception e) {
            log.error("SSE 구독 초기화 중 오류 - EmitterID: {}", emitterId, e);
            cleanupEmitter(emitterId);
        }

        return sseEmitter;
    }


    /**
     * 고객에게 알림 전송
     * @param customerId 고객 ID
     * @param notificationType 알림 타입
     * @param content 알림 내용
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendToCustomer(Long customerId, NotificationType notificationType, String content) {
        try {
            // 입력 값 검증
            if (customerId == null || notificationType == null || content == null || content.trim().isEmpty()) {
                log.warn("고객 알림 전송 실패 - 필수 파라미터 누락: customerId={}, type={}, content={}", 
                        customerId, notificationType, content);
                return;
            }

            // user 검증
            Customer customer = customerRepository.findById(customerId).orElse(null);
            if (customer == null) {
                log.warn("고객 알림 전송 실패 - 존재하지 않는 고객 ID: {}", customerId);
                return;
            }

            // DB에 알림 저장
            Notification notification = Notification.builder()
                    .customer(customer)
                    .content(content)
                    .notificationType(notificationType)
                    .build();

            Notification saved = notificationRepository.saveAndFlush(notification);
            log.info("고객 알림 DB 저장 완료 - 고객ID: {}, 알림ID: {}, 타입: {}",
                    customerId, saved.getNotificationId(), notificationType);

            // 포그라운드/백그라운드 분기하여 알림 전송
            sendNotificationWithStrategy(NotificationResponseDto.from(saved));
            
        } catch (Exception e) {
            log.error("고객 알림 전송 중 예상치 못한 오류 - 고객ID: {}, 타입: {}", customerId, notificationType, e);
        }
    }

    /**
     * 점주에게 알림 전송
     * @param ownerId 점주 ID
     * @param notificationType 알림 타입
     * @param content 알림 내용
     */
    public void sendToOwner(Long ownerId, NotificationType notificationType, String content) {
        try {
            // 입력 값 검증
            if (ownerId == null || notificationType == null || content == null || content.trim().isEmpty()) {
                log.warn("점주 알림 전송 실패 - 필수 파라미터 누락: ownerId={}, type={}, content={}", 
                        ownerId, notificationType, content);
                return;
            }
            
            Owner owner = ownerRepository.findById(ownerId).orElse(null);
            if (owner == null) {
                log.warn("점주 알림 전송 실패 - 존재하지 않는 점주 ID: {}", ownerId);
                return;
            }

            // DB에 알림 저장
            Notification notification = Notification.builder()
                    .owner(owner)
                    .content(content)
                    .notificationType(notificationType)
                    .build();

            Notification saved = notificationRepository.saveAndFlush(notification);
            log.info("점주 알림 DB 저장 완료 - 점주ID: {}, 알림ID: {}, 타입: {}", 
                    ownerId, saved.getNotificationId(), notificationType);

            // 포그라운드/백그라운드 분기하여 알림 전송
            sendNotificationWithStrategy(NotificationResponseDto.from(saved));
            
        } catch (Exception e) {
            log.error("점주 알림 전송 중 예상치 못한 오류 - 점주ID: {}, 타입: {}", ownerId, notificationType, e);
        }
    }

    /**
     * 고객에게 알림 전체 전송
     * @param customerIds 고객 ID들
     * @param notificationType 알림 타입
     * @param content 알림 내용
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendGroupSharedToMembers(List<Long> customerIds, NotificationType notificationType, String content) {
        if (customerIds == null || customerIds.isEmpty()) return;

        // 중복 제거
        for (Long cid : customerIds.stream().distinct().toList()) {
            try {
                Customer c = customerRepository.findById(cid).orElse(null);
                if (c == null) {
                    log.warn("알림 스킵 - 없는 고객 ID: {}", cid);
                    continue;
                }

                Notification n = Notification.builder()
                        .customer(c)
                        .notificationType(notificationType)
                        .content(content)
                        .build();

                Notification saved = notificationRepository.save(n);
                // 기존 전송 전략 그대로 재사용
                sendNotificationWithStrategy(NotificationResponseDto.from(saved));

            } catch (Exception e) {
                log.error("그룹 알림 중 오류 - 고객ID: {}", cid, e);
            }
        }
    }

    /**
     * 포그라운드/백그라운드/로그아웃 분기하여 알림 전송
     */
    private void sendNotificationWithStrategy(NotificationResponseDto data) {
        String receiverType = data.getReceiverType().toLowerCase();
        Long receiverId = data.getReceiverId();

        try {
            // 활성 SSE 연결이 있는지 확인
            boolean hasActiveConnection = emitterRepository.hasActiveConnection(receiverType, receiverId);

            if (hasActiveConnection) {
                // 1. 포그라운드: SSE로 실시간 전송
                log.info("포그라운드 상태 감지 - SSE로 알림 전송: {}:{}", receiverType, receiverId);
                sendRealTimeNotification(data);
            } else {
                // SSE 연결이 없는 경우: 로그인 상태 확인 필요
                boolean isLoggedIn = isUserLoggedIn(receiverType, receiverId);

                if (isLoggedIn) {
                    // 2. 백그라운드 (로그인 상태): FCM으로 푸시 알림 전송
                    log.info("백그라운드 상태 감지 - FCM으로 푸시 알림 전송: {}:{}", receiverType, receiverId);
                    sendFcmNotification(data);
                } else {
                    // 3. 로그아웃 상태: 알림 전송 차단
                    log.info("로그아웃 상태 감지 - 알림 전송 차단: {}:{}", receiverType, receiverId);
                    // DB에는 저장되지만 실시간/푸시 알림은 보내지 않음
                }
            }

        } catch (Exception e) {
            log.error("알림 전송 전략 선택 중 오류 - {}:{}", receiverType, receiverId, e);
        }
    }
    
    /**
     * FCM 푸시 알림 전송
     */
    private void sendFcmNotification(NotificationResponseDto data) {
        String receiverType = data.getReceiverType().toLowerCase();
        Long receiverId = data.getReceiverId();
        String title = "새 알림";
        String body = data.getContent();
        
        try {
            // 알림 타입별 제목 설정
            if (data.getNotificationType() != null) {
                title = data.getNotificationType().getDisplayName();
            }
            
            // 추가 데이터 설정
            Map<String, String> fcmData = Map.of(
                "notificationId", data.getNotificationId().toString(),
                "type", data.getNotificationType() != null ? data.getNotificationType().toString() : "UNKNOWN",
                "createdAt", data.getCreatedAt().toString()
            );
            
            if ("customer".equals(receiverType)) {
                fcmService.sendToCustomer(receiverId, data.getNotificationType(), title, body, fcmData);
            } else if ("owner".equals(receiverType)) {
                fcmService.sendToOwner(receiverId, data.getNotificationType(), title, body, fcmData);
            }
            
            log.info("FCM 푸시 알림 전송 완료 - {}:{}, 제목: {}", receiverType, receiverId, title);
            
        } catch (Exception e) {
            log.error("FCM 푸시 알림 전송 실패 - {}:{}", receiverType, receiverId, e);
        }
    }

    /**
     * 실시간 알림 전송 (SSE) - 총괄 메니저 역할
     */
    // receiverType : customer, owner
    private void sendRealTimeNotification(NotificationResponseDto data) {
        String receiverType = data.getReceiverType().toLowerCase();
        Long receiverId = data.getReceiverId();

        try {
            Map<String, SseEmitter> emitters = emitterRepository.findAllEmitterStartWithByReceiver(receiverType, receiverId);
            
            if (emitters.isEmpty()) {
                log.info("연결된 사용자 없음 - {}:{}, 알림을 캐시에만 저장", receiverType, receiverId);
                return;
            }
            
            // 하나의 알림에 대해 단일 eventId 생성 (모든 연결에서 동일하게 사용)
            String eventId = makeTimeIncludeId(receiverType, receiverId);
            
            // 이벤트 캐시에 한 번만 저장 (중복 저장 방지)
            emitterRepository.saveEventCache(eventId, data);
            
            // 단순 카운터
            int successCount = 0;
            int failCount = 0;
            
            // 모든 emitter에 순차 전송
            for (Map.Entry<String, SseEmitter> entry : emitters.entrySet()) {
                String emitterId = entry.getKey();
                SseEmitter emitter = entry.getValue();
                
                try {
                    boolean success = sendNotification(emitter, eventId, emitterId, data);
                    if (success) {
                        successCount++;
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    log.warn("개별 SSE 전송 실패 - EmitterID: {}, 사유: {}", emitterId, e.getMessage());
                }
            }
            
            log.info("실시간 알림 전송 완료 - {}:{}, EventID: {}, 총 연결: {}, 성공: {}, 실패: {}", 
                    receiverType, receiverId, eventId, emitters.size(), successCount, failCount);
                    
        } catch (Exception e) {
            log.error("실시간 알림 전송 중 예상치 못한 오류 - {}:{}", receiverType, receiverId, e);
        }
    }

    /**
     * SSE로 알림 전송 ( 실제로 알림 전송 - 네트워크 관여 )
     * @return 전송 성공 여부
     */
    private boolean sendNotification(SseEmitter emitter, String eventId, String emitterId, Object data) {
        try {
            emitter.send(SseEmitter.event()
                    .id(eventId)
                    .name("notification")
                    .data(data));
            return true;
            
        } catch (IOException e) {
            log.warn("SSE 전송 실패 - EmitterID: {}, EventID: {}, 오류: {}", 
                    emitterId, eventId, e.getMessage());
            
            // 연결이 끊어진 Emitter 정리
            cleanupEmitter(emitterId);
            return false;
            
        } catch (Exception e) {
            log.error("SSE 전송 중 예상치 못한 오류 - EmitterID: {}, EventID: {}", 
                    emitterId, eventId, e);
            
            // 예상치 못한 오류 시에도 Emitter 정리
            cleanupEmitter(emitterId);
            return false;
        }
    }

    /**
     * Emitter 안전 정리 - 제대로 응답 안하는 id 하나만 삭제함
     */
    private void cleanupEmitter(String emitterId) {
        try {
            emitterRepository.deleteById(emitterId);
        } catch (Exception e) {
            log.warn("Emitter 정리 중 오류 - EmitterID: {}, 오류: {}", emitterId, e.getMessage());
        }
    }

    /**
     * 유니크한 ID 생성
     */
    private String makeTimeIncludeId(String receiverType, Long receiverId) {
        return receiverType + "-" + receiverId + "_" + System.currentTimeMillis();
    }


    /**
     * AccessToken에서 발급시간 추출
     */
    private LocalDateTime getTokenIssuedAt(String accessToken) {
        try {
            if (accessToken == null || accessToken.trim().isEmpty()) {
                return null;
            }

            Date issuedAt = accessTokenService.getIssuedAt(accessToken);
            if (issuedAt == null) {
                return null;
            }

            return issuedAt.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception e) {
            log.warn("AccessToken 발급시간 추출 실패 - 토큰: {}, 오류: {}",
                    accessToken != null ? accessToken.substring(0, Math.min(accessToken.length(), 20)) + "..." : "null",
                    e.getMessage());
            return null;
        }
    }

    /**
     * 이벤트가 토큰 발급시간 이후에 생성되었는지 확인
     */
    private boolean isEventAfterTokenTime(Object eventData, LocalDateTime tokenIssuedAt) {
        try {
            if (tokenIssuedAt == null || eventData == null) {
                return false;
            }

            if (eventData instanceof NotificationResponseDto) {
                NotificationResponseDto notification = (NotificationResponseDto) eventData;
                String createdAtStr = notification.getCreatedAt();

                if (createdAtStr == null || createdAtStr.isEmpty()) {
                    return false;
                }

                // String 형태의 createdAt을 LocalDateTime으로 파싱
                LocalDateTime eventCreatedAt = LocalDateTime.parse(createdAtStr,
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                return eventCreatedAt.isAfter(tokenIssuedAt);
            }

            return false;
        } catch (Exception e) {
            log.warn("이벤트 시간 비교 실패 - 토큰발급시간: {}, 오류: {}", tokenIssuedAt, e.getMessage());
            return false;
        }
    }

    /**
     * 토큰 기반 유실 데이터 재전송
     */
    private void sendLostDataWithTokenFilter(String accessToken, String receiverType, Long receiverId,
                                           String emitterId, SseEmitter emitter) {
        try {
            LocalDateTime tokenIssuedAt = getTokenIssuedAt(accessToken);
            if (tokenIssuedAt == null) {
                log.warn("토큰 발급시간을 확인할 수 없어 재전송을 차단합니다 - {}:{}", receiverType, receiverId);
                return;
            }

            Map<String, Object> eventCaches = emitterRepository.findAllEventCacheStartWithByReceiver(receiverType, receiverId);

            if (eventCaches.isEmpty()) {
                log.info("재전송할 캐시 이벤트가 없습니다 - {}:{}", receiverType, receiverId);
                return;
            }

            int totalEvents = eventCaches.size();
            int sentEvents = 0;

            for (Map.Entry<String, Object> entry : eventCaches.entrySet()) {
                if (isEventAfterTokenTime(entry.getValue(), tokenIssuedAt)) {
                    boolean success = sendNotification(emitter, entry.getKey(), emitterId, entry.getValue());
                    if (success) {
                        sentEvents++;
                    }
                }
            }

            log.info("토큰 기반 유실 데이터 재전송 완료 - {}:{}, 토큰발급시간: {}, 총 캐시: {}, 재전송: {}",
                    receiverType, receiverId, tokenIssuedAt, totalEvents, sentEvents);

        } catch (Exception e) {
            log.error("토큰 기반 유실 데이터 재전송 중 오류 - {}:{}", receiverType, receiverId, e);
        }
    }

    /**
     * 사용자 로그인 상태 확인 (Redis RefreshToken 존재 여부 확인)
     * @param receiverType "customer" 또는 "owner"
     * @param receiverId 사용자 ID
     * @return 로그인 상태
     */
    private boolean isUserLoggedIn(String receiverType, Long receiverId) {
        try {
            UserRole userRole = convertToUserRole(receiverType);
            if (userRole == null) {
                log.warn("유효하지 않은 receiverType: {}", receiverType);
                return false;
            }

            // TokenService와 동일한 키 패턴 사용: "auth:rt:" + userRole + ":" + userId
            String refreshTokenKey = "auth:rt:" + userRole.name() + ":" + receiverId;

            // Redis에서 RefreshToken 존재 여부 확인
            boolean hasRefreshToken = redisTemplate.hasKey(refreshTokenKey);

            log.debug("로그인 상태 확인 - {}:{}, RefreshToken 존재: {}", receiverType, receiverId, hasRefreshToken);

            return hasRefreshToken;

        } catch (Exception e) {
            log.error("로그인 상태 확인 중 오류 - {}:{}", receiverType, receiverId, e);
            // 예외 발생 시 안전하게 true 반환 (알림 차단보다는 전송이 나음)
            return true;
        }
    }

    /**
     * receiverType을 UserRole로 변환
     */
    private UserRole convertToUserRole(String receiverType) {
        if ("customer".equalsIgnoreCase(receiverType)) {
            return UserRole.CUSTOMER;
        } else if ("owner".equalsIgnoreCase(receiverType)) {
            return UserRole.OWNER;
        }
        return null;
    }
}