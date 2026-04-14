package com.ssafy.keeping.domain.notification.controller;

import com.ssafy.keeping.domain.notification.dto.FcmTokenRequestDto;
import com.ssafy.keeping.domain.notification.service.FcmService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

@RestController
@RequestMapping("/api/fcm")
@RequiredArgsConstructor
@Slf4j
@Validated
public class FcmController {

    private final FcmService fcmService;

    /**
     * 고객 FCM 토큰 등록
     * 
     * @param customerId 고객 ID
     * @param request FCM 토큰 정보
     * @return 등록 결과
     */
    @PostMapping("/customer/{customerId}/token")
    public ResponseEntity<ApiResponse<String>> registerCustomerToken(
            @PathVariable @Positive(message = "고객 ID는 양수여야 합니다.") Long customerId,
            @RequestBody @Valid FcmTokenRequestDto request) {
        
        log.info("고객 FCM 토큰 등록 요청 - 고객ID: {}", customerId);
        
        fcmService.registerCustomerToken(customerId, request.getToken());
        
        log.info("고객 FCM 토큰 등록 성공 - 고객ID: {}", customerId);
        return ResponseEntity.ok(
            ApiResponse.success("FCM 토큰이 등록되었습니다.", HttpStatus.OK.value(), "토큰 등록 완료")
        );
    }

    /**
     * 점주 FCM 토큰 등록
     * 
     * @param ownerId 점주 ID
     * @param request FCM 토큰 정보
     * @return 등록 결과
     */
    @PostMapping("/owner/{ownerId}/token")
    public ResponseEntity<ApiResponse<String>> registerOwnerToken(
            @PathVariable @Positive(message = "점주 ID는 양수여야 합니다.") Long ownerId,
            @RequestBody @Valid FcmTokenRequestDto request) {
        
        log.info("점주 FCM 토큰 등록 요청 - 점주ID: {}", ownerId);
        
        fcmService.registerOwnerToken(ownerId, request.getToken());
        
        log.info("점주 FCM 토큰 등록 성공 - 점주ID: {}", ownerId);
        return ResponseEntity.ok(
            ApiResponse.success("FCM 토큰이 등록되었습니다.", HttpStatus.OK.value(), "토큰 등록 완료")
        );
    }

    /**
     * FCM 토큰 삭제
     * 
     * @param request FCM 토큰 정보
     * @return 삭제 결과
     */
    @DeleteMapping("/token")
    public ResponseEntity<ApiResponse<String>> deleteToken(
            @RequestBody @Valid FcmTokenRequestDto request) {
        
        log.info("FCM 토큰 삭제 요청 - 토큰: {}", request.getToken().substring(0, 20) + "...");
        
        fcmService.deleteToken(request.getToken());
        
        log.info("FCM 토큰 삭제 성공");
        return ResponseEntity.ok(
            ApiResponse.success("FCM 토큰이 삭제되었습니다.", HttpStatus.OK.value(), "토큰 삭제 완료")
        );
    }
}