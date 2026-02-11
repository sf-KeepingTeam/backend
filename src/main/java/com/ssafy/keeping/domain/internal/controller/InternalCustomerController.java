package com.ssafy.keeping.domain.internal.controller;

import com.ssafy.keeping.domain.auth.pin.service.PinAuthService;
import com.ssafy.keeping.domain.internal.dto.CustomerResponse;
import com.ssafy.keeping.domain.internal.dto.PinVerifyRequest;
import com.ssafy.keeping.domain.internal.dto.PinVerifyResponse;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Internal API - 마이크로서비스 간 통신용
 */
@Slf4j
@RestController
@RequestMapping("/internal/customers")
@RequiredArgsConstructor
public class InternalCustomerController {

    private final CustomerRepository customerRepository;
    private final PinAuthService pinAuthService;

    private static final String INTERNAL_AUTH_TOKEN = "internal-service-token-12345";

    /**
     * 고객 정보 조회
     */
    @GetMapping("/{customerId}")
    public ResponseEntity<CustomerResponse> getCustomer(
            @PathVariable Long customerId,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        Customer customer = customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        return ResponseEntity.ok(CustomerResponse.from(customer));
    }

    /**
     * PIN 검증
     */
    @PostMapping("/{customerId}/pin-verify")
    public ResponseEntity<PinVerifyResponse> verifyPin(
            @PathVariable Long customerId,
            @RequestBody PinVerifyRequest request,
            @RequestHeader(value = "X-Internal-Auth", required = false) String authToken
    ) {
        validateInternalAuth(authToken);

        // 고객 존재 여부 확인
        customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        // PIN 검증
        boolean pinMatches = pinAuthService.verify(customerId, request.getPin());

        return ResponseEntity.ok(PinVerifyResponse.builder()
                .verified(pinMatches)
                .customerId(customerId)
                .build());
    }

    private void validateInternalAuth(String authToken) {
        if (!INTERNAL_AUTH_TOKEN.equals(authToken)) {
            log.warn("Internal API 인증 실패: 잘못된 토큰");
            throw new IllegalArgumentException("Internal API 인증 실패");
        }
    }
}
