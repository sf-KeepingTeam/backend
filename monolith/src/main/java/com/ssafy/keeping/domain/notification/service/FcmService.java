package com.ssafy.keeping.domain.notification.service;

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
     * FCM 메시지 전송 (Stub)
     *
     * Firebase Admin SDK 의존성 제거 — 실제 푸시 발송 대신 로그만 출력한다.
     */
    private void sendMessage(String token, String title, String body, Map<String, String> data) {
        log.info("[FCM Stub] 알림 발송 — token: {}..., title: {}, body: {}, data: {}",
                token.substring(0, Math.min(token.length(), 20)), title, body, data);
    }
}
