package com.ssafy.keeping.domain.wallet.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.idempotency.model.IdempotentResult;
import com.ssafy.keeping.domain.wallet.dto.*;
import com.ssafy.keeping.domain.wallet.service.WalletService;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.PageRequest;

@RestController
@RequestMapping("/wallets")
@RequiredArgsConstructor
public class WalletController {
    private final WalletService walletService;

    @GetMapping("/groups/{groupId}")
    public ResponseEntity<ApiResponse<WalletResponseDto>> getGroupWallets(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId
    ) {
        Long customerId = principal.id();
        WalletResponseDto dto = walletService.getGroupWallet(groupId, customerId);
        return ResponseEntity.ok(ApiResponse.success("모임 지갑 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    // 모임 <-> 가게별 공유
    @PostMapping("/groups/{groupId}/stores/{storeId}")
    public ResponseEntity<ApiResponse<PointShareResponseDto>> createSharePoints(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @PathVariable Long storeId,
            @RequestHeader("Idempotency-Key") String idemKey,
            @RequestBody @Valid PointShareRequestDto req
    ) {
        Long customerId = principal.id();
        IdempotentResult<PointShareResponseDto> result =
                walletService.sharePoints(groupId, customerId, storeId, idemKey, req);

        HttpStatus status = result.getHttpStatus();
        String msg;
        if (result.isReplay()) {
            msg = "이전에 처리된 포인트 공유 결과를 반환합니다.";
        } else if (status == HttpStatus.ACCEPTED) {
            ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
            if (result.getRetryAfterSeconds() != null) {
                b.header("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
            }
            return b.body(ApiResponse.success("포인트 공유가 처리 중입니다. 잠시 후 다시 시도하세요.", status.value(), null));
        } else {
            msg = "모임 지갑으로 포인트 공유를 완료했습니다.";
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.success(msg, status.value(), result.getBody()));
    }

    @GetMapping("/{walletId}/stores/{storeId}/points/available")
    public ResponseEntity<ApiResponse<AvailablePointResponseDto>> getReclaimable(
            @PathVariable Long walletId,
            @PathVariable Long storeId,
            @AuthenticationPrincipal UserPrincipal principal
    ) {
        Long customerId = principal.id();
        AvailablePointResponseDto dto = walletService.getReclaimablePoints(walletId, storeId, customerId);
        return ResponseEntity.ok(
                ApiResponse.success("회수 가능한 포인트를 조회했습니다.", HttpStatus.OK.value(), dto)
        );
    }

    @PostMapping("/groups/{groupId}/stores/{storeId}/reclaim")
    public ResponseEntity<ApiResponse<PointShareResponseDto>> reclaimPoints(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long groupId,
            @PathVariable Long storeId,
            @RequestHeader("Idempotency-Key") String idemKey,
            @RequestBody @Valid PointShareRequestDto req
    ) {
        Long customerId = principal.id();
        IdempotentResult<PointShareResponseDto> result =
                walletService.reclaimPoints(groupId, customerId, storeId, idemKey, req);

        HttpStatus status = result.getHttpStatus();
        if (result.isReplay()) {
            return ResponseEntity.status(status)
                    .body(ApiResponse.success("이전에 처리된 포인트 회수 결과를 반환합니다.", status.value(), result.getBody()));
        }
        if (status == HttpStatus.ACCEPTED) {
            ResponseEntity.BodyBuilder b = ResponseEntity.status(status);
            if (result.getRetryAfterSeconds() != null) {
                b.header("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
            }
            return b.body(ApiResponse.success("포인트 회수가 처리 중입니다. 잠시 후 다시 시도하세요.", status.value(), null));
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.success("모임 지갑에서 포인트를 회수했습니다.", status.value(), result.getBody()));
    }

    // 개인 지갑 잔액
    @GetMapping("/individual/balance")
    public ResponseEntity<ApiResponse<PersonalWalletBalanceResponseDto>> getPersonalWalletBalance(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Long customerId = principal.id();
        Pageable pageable = PageRequest.of(page, size);
        PersonalWalletBalanceResponseDto dto = walletService.getPersonalWalletBalance(customerId, pageable);
        return ResponseEntity.ok(ApiResponse.success("개인 지갑 잔액 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    // 모임 지갑 잔액
    @GetMapping("/groups/{groupId}/balance")
    public ResponseEntity<ApiResponse<GroupWalletBalanceResponseDto>> getGroupWalletBalance(
            @PathVariable Long groupId,
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Long customerId = principal.id();
        Pageable pageable = PageRequest.of(page, size);
        GroupWalletBalanceResponseDto dto = walletService.getGroupWalletBalance(groupId, customerId, pageable);
        return ResponseEntity.ok(ApiResponse.success("모임 지갑 잔액 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    /**
     * 개인지갑 - 특정 가게의 상세 정보 조회
     */
    @GetMapping("/individual/stores/{storeId}/detail")
    public ResponseEntity<ApiResponse<WalletStoreDetailResponseDto>> getPersonalWalletStoreDetail(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Long customerId = principal.id();
        Pageable pageable = PageRequest.of(page, size);
        WalletStoreDetailResponseDto dto = walletService.getPersonalWalletStoreDetail(customerId, storeId, pageable);
        return ResponseEntity.ok(ApiResponse.success("개인 지갑 가게별 상세 정보 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    /**
     * 모임지갑 - 특정 가게의 상세 정보 조회
     */
    @GetMapping("/groups/{groupId}/stores/{storeId}/detail")
    public ResponseEntity<ApiResponse<WalletStoreDetailResponseDto>> getGroupWalletStoreDetail(
            @PathVariable Long groupId,
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable Long storeId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Long customerId = principal.id();
        Pageable pageable = PageRequest.of(page, size);
        WalletStoreDetailResponseDto dto = walletService.getGroupWalletStoreDetail(groupId, customerId, storeId, pageable);
        return ResponseEntity.ok(ApiResponse.success("모임 지갑 가게별 상세 정보 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }

    /**
     * 개인 지갑 + 모임 지갑 한번에 조회
     */
    @GetMapping("/both/balance")
    public ResponseEntity<ApiResponse<BothWalletBalanceResponseDto>> getBothWalletBalance(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        Long customerId = principal.id();
        Pageable pageable = PageRequest.of(page, size);
        BothWalletBalanceResponseDto dto = walletService.getBothWalletBalance(customerId, pageable);
        return ResponseEntity.ok(ApiResponse.success("개인 지갑 및 모임 지갑 조회에 성공했습니다.", HttpStatus.OK.value(), dto));
    }
}
