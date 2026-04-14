package com.ssafy.keeping.domain.notification.service;

import com.google.firebase.messaging.*;
import com.ssafy.keeping.domain.notification.entity.FcmToken;
import com.ssafy.keeping.domain.notification.entity.NotificationType;
import com.ssafy.keeping.domain.notification.repository.FcmTokenRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class FcmService {

    private final FcmTokenRepository fcmTokenRepository;
    private final CustomerRepository customerRepository;
    private final OwnerRepository ownerRepository;

    /**
     * 고객 FCM 토큰 등록/업데이트
     */
    @Transactional
    public void registerCustomerToken(Long customerId, String token) {
        log.info("고객 FCM 토큰 등록 요청 - 고객ID: {}, 토큰: {}", customerId, token.substring(0, 20));

        Customer customer = customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.CUSTOMER_NOT_FOUND));

        // 기존 토큰이 있으면 업데이트, 없으면 새로 생성
        FcmToken fcmToken = fcmTokenRepository.findByCustomerIdAndToken(customerId, token)
                .orElse(null);

        if (fcmToken != null) {
            fcmToken.updateToken(token);
            log.info("기존 고객 FCM 토큰 업데이트 완료 - 고객ID: {}", customerId);
        } else {
            fcmToken = FcmToken.builder()
                    .token(token)
                    .customer(customer)
                    .build();
            fcmTokenRepository.save(fcmToken);
            log.info("새 고객 FCM 토큰 등록 완료 - 고객ID: {}", customerId);
        }
    }

    /**
     * 점주 FCM 토큰 등록/업데이트
     */
    @Transactional
    public void registerOwnerToken(Long ownerId, String token) {
        log.info("점주 FCM 토큰 등록 요청 - 점주ID: {}, 토큰: {}", ownerId, token.substring(0, 20));

        Owner owner = ownerRepository.findByOwnerIdAndDeletedAtIsNull(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.OWNER_NOT_FOUND));

        // 기존 토큰이 있으면 업데이트, 없으면 새로 생성
        FcmToken fcmToken = fcmTokenRepository.findByOwnerIdAndToken(ownerId, token)
                .orElse(null);

        if (fcmToken != null) {
            fcmToken.updateToken(token);
            log.info("기존 점주 FCM 토큰 업데이트 완료 - 점주ID: {}", ownerId);
        } else {
            fcmToken = FcmToken.builder()
                    .token(token)
                    .owner(owner)
                    .build();
            fcmTokenRepository.save(fcmToken);
            log.info("새 점주 FCM 토큰 등록 완료 - 점주ID: {}", ownerId);
        }
    }

    /**
     * FCM 토큰 삭제
     */
    @Transactional
    public void deleteToken(String token) {
        log.info("FCM 토큰 삭제 요청 - 토큰: {}", token.substring(0, 20) + "...");
        
        fcmTokenRepository.deleteByToken(token);
        log.info("FCM 토큰 삭제 완료");
    }

    /**
     * 고객에게 FCM 푸시 알림 전송
     */
    public void sendToCustomer(Long customerId, NotificationType type, String title, String body, Map<String, String> data) {
        log.info("고객에게 FCM 알림 전송 - 고객ID: {}, 제목: {}", customerId, title);

        List<FcmToken> tokens = fcmTokenRepository.findByCustomerId(customerId);
        if (tokens.isEmpty()) {
            log.warn("고객의 FCM 토큰이 없음 - 고객ID: {}", customerId);
            return;
        }

        for (FcmToken fcmToken : tokens) {
            sendMessage(fcmToken.getToken(), title, body, data);
        }
    }

    /**
     * 점주에게 FCM 푸시 알림 전송
     */
    public void sendToOwner(Long ownerId, NotificationType type, String title, String body, Map<String, String> data) {
        log.info("점주에게 FCM 알림 전송 - 점주ID: {}, 제목: {}", ownerId, title);

        List<FcmToken> tokens = fcmTokenRepository.findByOwnerId(ownerId);
        if (tokens.isEmpty()) {
            log.warn("점주의 FCM 토큰이 없음 - 점주ID: {}", ownerId);
            return;
        }

        for (FcmToken fcmToken : tokens) {
            sendMessage(fcmToken.getToken(), title, body, data);
        }
    }

    /**
     * FCM 메시지 전송
     */
    private void sendMessage(String token, String title, String body, Map<String, String> data) {
        try {
            Message.Builder messageBuilder = Message.builder()
                    .setToken(token)
                    .setNotification(Notification.builder()
                            .setTitle(title)
                            .setBody(body)
                            .build())
                    .setWebpushConfig(WebpushConfig.builder()
                            .setNotification(WebpushNotification.builder()
                                    .setTitle(title)
                                    .setBody(body)
                                    .setIcon("/icon-192x192.png") // 웹 푸시 아이콘
                                    .build())
                            .build());

            // 추가 데이터가 있으면 포함
            if (data != null && !data.isEmpty()) {
                messageBuilder.putAllData(data);
            }

            Message message = messageBuilder.build();
            String response = FirebaseMessaging.getInstance().send(message);
            
            log.info("FCM 메시지 전송 성공 - 응답: {}", response);
            
        } catch (FirebaseMessagingException e) {
            log.error("FCM 메시지 전송 실패 - 토큰: {}, 오류: {}", 
                    token.substring(0, 20) + "...", e.getMessage());
            
            // 유효하지 않은 토큰이면 DB에서 삭제
            if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED ||
                e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                log.info("유효하지 않은 토큰 삭제 - 토큰: {}", token.substring(0, 20) + "...");
                fcmTokenRepository.deleteByToken(token);
            }
        } catch (Exception e) {
            log.error("FCM 메시지 전송 중 예상치 못한 오류", e);
        }
    }
}