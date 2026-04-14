package com.ssafy.keeping.domain.charge.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.charge.dto.request.ChargeBonusRequestDto;
import com.ssafy.keeping.domain.charge.dto.response.ChargeBonusListResponseDto;
import com.ssafy.keeping.domain.charge.dto.response.ChargeBonusResponseDto;
import com.ssafy.keeping.domain.charge.service.ChargeBonusService;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.Positive;
import java.util.List;

@RestController
@RequestMapping("/owners/stores/{storeId}/charge-bonus")
@RequiredArgsConstructor
@Slf4j
@Validated
public class ChargeBonusController {

    private final ChargeBonusService chargeBonusService;

    /**
     * 충전 보너스 설정 생성
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ChargeBonusResponseDto>> createChargeBonus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
            @RequestBody @Valid ChargeBonusRequestDto requestDto) {
        Long ownerId = principal.id();

        log.info("충전 보너스 설정 생성 요청 수신 - 점주ID: {}, 가게ID: {}", ownerId, storeId);

        ChargeBonusResponseDto responseDto = chargeBonusService.createChargeBonus(ownerId, storeId, requestDto);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("충전 보너스 설정이 생성되었습니다.", HttpStatus.CREATED.value(), responseDto));
    }

    /**
     * 충전 보너스 설정 목록 조회
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ChargeBonusListResponseDto>>> getChargeBonusList(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId) {
        Long ownerId = principal.id();

        log.info("충전 보너스 설정 목록 조회 요청 수신 - 점주ID: {}, 가게ID: {}", ownerId, storeId);

        List<ChargeBonusListResponseDto> responseDtoList = chargeBonusService.getChargeBonusList(ownerId, storeId);

        return ResponseEntity.ok()
                .body(ApiResponse.success("충전 보너스 설정 목록이 조회되었습니다.", HttpStatus.OK.value(), responseDtoList));
    }

    /**
     * 충전 보너스 설정 상세 조회
     */
    @GetMapping("/{chargeBonusId}")
    public ResponseEntity<ApiResponse<ChargeBonusResponseDto>> getChargeBonus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
            @PathVariable @Positive(message = "충전 보너스 ID는 양수여야 합니다.") Long chargeBonusId) {
        Long ownerId = principal.id();

        log.info("충전 보너스 설정 상세 조회 요청 수신 - 점주ID: {}, 가게ID: {}, 보너스ID: {}", ownerId, storeId, chargeBonusId);

        ChargeBonusResponseDto responseDto = chargeBonusService.getChargeBonus(ownerId, storeId, chargeBonusId);

        return ResponseEntity.ok()
                .body(ApiResponse.success("충전 보너스 설정이 조회되었습니다.", HttpStatus.OK.value(), responseDto));
    }

    /**
     * 충전 보너스 설정 수정
     */
    @PutMapping("/{chargeBonusId}")
    public ResponseEntity<ApiResponse<ChargeBonusResponseDto>> updateChargeBonus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
            @PathVariable @Positive(message = "충전 보너스 ID는 양수여야 합니다.") Long chargeBonusId,
            @RequestBody @Valid ChargeBonusRequestDto requestDto) {
        Long ownerId = principal.id();

        log.info("충전 보너스 설정 수정 요청 수신 - 점주ID: {}, 가게ID: {}, 보너스ID: {}", ownerId, storeId, chargeBonusId);

        ChargeBonusResponseDto responseDto = chargeBonusService.updateChargeBonus(ownerId, storeId, chargeBonusId, requestDto);

        return ResponseEntity.ok()
                .body(ApiResponse.success("충전 보너스 설정이 수정되었습니다.", HttpStatus.OK.value(), responseDto));
    }

    /**
     * 충전 보너스 설정 삭제
     */
    @DeleteMapping("/{chargeBonusId}")
    public ResponseEntity<ApiResponse<Void>> deleteChargeBonus(
            @AuthenticationPrincipal UserPrincipal principal,
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId,
            @PathVariable @Positive(message = "충전 보너스 ID는 양수여야 합니다.") Long chargeBonusId) {
        Long ownerId = principal.id();

        log.info("충전 보너스 설정 삭제 요청 수신 - 점주ID: {}, 가게ID: {}, 보너스ID: {}", ownerId, storeId, chargeBonusId);

        chargeBonusService.deleteChargeBonus(ownerId, storeId, chargeBonusId);

        return ResponseEntity.ok()
                .body(ApiResponse.success("충전 보너스 설정이 삭제되었습니다.", HttpStatus.OK.value(), null));
    }
}