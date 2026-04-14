package com.ssafy.keeping.domain.charge.controller;

import com.ssafy.keeping.domain.charge.dto.response.PublicChargeBonusResponseDto;
import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import com.ssafy.keeping.domain.charge.repository.ChargeBonusRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Positive;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/stores/{storeId}/charge-bonus")
@RequiredArgsConstructor
@Slf4j
@Validated
public class StoreChargeBonusController {

    private final ChargeBonusRepository chargeBonusRepository;
    private final StoreRepository storeRepository;

    /**
     * 고객용 충전 보너스 목록 조회
     * 만약 10000원을 충전하면 11000포인트를 줄거다! 하는거임 (그 목록을 프론트에게 보내주기 위함)
     * 가게 페이지 보여줄 때 필요할 듯
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<PublicChargeBonusResponseDto>>> getPublicChargeBonusList(
            @PathVariable @Positive(message = "가게 ID는 양수여야 합니다.") Long storeId) {

        log.info("고객용 충전 보너스 목록 조회 요청 - 가게ID: {}", storeId);

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        List<ChargeBonus> bonusList = chargeBonusRepository.findByStore(store);

        List<PublicChargeBonusResponseDto> responseDtoList = bonusList.stream()
                .map(PublicChargeBonusResponseDto::from)
                .collect(Collectors.toList());

        log.info("고객용 충전 보너스 목록 조회 완료 - 가게ID: {}, 항목 수: {}", storeId, responseDtoList.size());

        return ResponseEntity.ok()
                .body(ApiResponse.success("충전 보너스 목록이 조회되었습니다.", HttpStatus.OK.value(), responseDtoList));
    }
}