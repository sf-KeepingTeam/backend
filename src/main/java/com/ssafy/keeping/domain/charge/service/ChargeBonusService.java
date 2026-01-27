package com.ssafy.keeping.domain.charge.service;

import com.ssafy.keeping.domain.charge.dto.request.ChargeBonusRequestDto;
import com.ssafy.keeping.domain.charge.dto.response.ChargeBonusListResponseDto;
import com.ssafy.keeping.domain.charge.dto.response.ChargeBonusResponseDto;
import com.ssafy.keeping.domain.charge.model.ChargeBonus;
import com.ssafy.keeping.domain.charge.repository.ChargeBonusRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class ChargeBonusService {

    private final ChargeBonusRepository chargeBonusRepository;
    private final StoreRepository storeRepository;

    public ChargeBonusResponseDto createChargeBonus(Long ownerId, Long storeId, ChargeBonusRequestDto requestDto) {
        log.info("충전 보너스 설정 생성 요청 - 점주ID: {}, 가게ID: {}, 충전금액: {}, 보너스: {}%",
                ownerId, storeId, requestDto.getChargeAmount(), requestDto.getBonusPercentage());

        Store store = validateStoreOwnership(ownerId, storeId);

        if (chargeBonusRepository.existsByStoreIdAndChargeAmount(storeId, requestDto.getChargeAmount())) {
            throw new CustomException(ErrorCode.CHARGE_BONUS_ALREADY_EXISTS);
        }

        ChargeBonus chargeBonus = ChargeBonus.builder()
                .storeId(storeId)
                .chargeAmount(requestDto.getChargeAmount())
                .bonusPercentage(requestDto.getBonusPercentage())
                .build();

        ChargeBonus savedChargeBonus = chargeBonusRepository.save(chargeBonus);
        log.info("충전 보너스 설정 생성 완료 - ID: {}", savedChargeBonus.getChargeBonusId());

        return ChargeBonusResponseDto.from(savedChargeBonus, store.getStoreId(), store.getStoreName());
    }

    @Transactional(readOnly = true)
    public List<ChargeBonusListResponseDto> getChargeBonusList(Long ownerId, Long storeId) {
        log.info("충전 보너스 설정 목록 조회 - 점주ID: {}, 가게ID: {}", ownerId, storeId);

        validateStoreOwnership(ownerId, storeId);
        List<ChargeBonus> chargeBonusList = chargeBonusRepository.findByStoreId(storeId);

        return chargeBonusList.stream()
                .map(ChargeBonusListResponseDto::from)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ChargeBonusResponseDto getChargeBonus(Long ownerId, Long storeId, Long chargeBonusId) {
        log.info("충전 보너스 설정 상세 조회 - 점주ID: {}, 가게ID: {}, 보너스ID: {}", ownerId, storeId, chargeBonusId);

        Store store = validateStoreOwnership(ownerId, storeId);
        ChargeBonus chargeBonus = findChargeBonusById(chargeBonusId);

        if (!chargeBonus.getStoreId().equals(storeId)) {
            throw new CustomException(ErrorCode.CHARGE_BONUS_NOT_FOUND);
        }

        return ChargeBonusResponseDto.from(chargeBonus, store.getStoreId(), store.getStoreName());
    }

    public ChargeBonusResponseDto updateChargeBonus(Long ownerId, Long storeId, Long chargeBonusId, ChargeBonusRequestDto requestDto) {
        log.info("충전 보너스 설정 수정 요청 - 점주ID: {}, 가게ID: {}, 보너스ID: {}", ownerId, storeId, chargeBonusId);

        Store store = validateStoreOwnership(ownerId, storeId);
        ChargeBonus chargeBonus = findChargeBonusById(chargeBonusId);

        if (!chargeBonus.getStoreId().equals(storeId)) {
            throw new CustomException(ErrorCode.CHARGE_BONUS_NOT_FOUND);
        }

        if (chargeBonusRepository.existsByStoreIdAndChargeAmountExcludingId(storeId, requestDto.getChargeAmount(), chargeBonusId)) {
            throw new CustomException(ErrorCode.CHARGE_BONUS_ALREADY_EXISTS);
        }

        chargeBonus.updateChargeBonus(requestDto.getChargeAmount(), requestDto.getBonusPercentage());
        log.info("충전 보너스 설정 수정 완료 - ID: {}", chargeBonusId);

        return ChargeBonusResponseDto.from(chargeBonus, store.getStoreId(), store.getStoreName());
    }

    public void deleteChargeBonus(Long ownerId, Long storeId, Long chargeBonusId) {
        log.info("충전 보너스 설정 삭제 요청 - 점주ID: {}, 가게ID: {}, 보너스ID: {}", ownerId, storeId, chargeBonusId);

        validateStoreOwnership(ownerId, storeId);
        ChargeBonus chargeBonus = findChargeBonusById(chargeBonusId);

        if (!chargeBonus.getStoreId().equals(storeId)) {
            throw new CustomException(ErrorCode.CHARGE_BONUS_NOT_FOUND);
        }

        chargeBonusRepository.delete(chargeBonus);
        log.info("충전 보너스 설정 삭제 완료 - ID: {}", chargeBonusId);
    }

    @Transactional(readOnly = true)
    public Optional<ChargeBonus> findChargeBonusByAmount(Long storeId, Long chargeAmount) {
        storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        return chargeBonusRepository.findByStoreIdAndChargeAmount(storeId, chargeAmount);
    }

    private Store validateStoreOwnership(Long ownerId, Long storeId) {
        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        if (!store.getOwnerId().equals(ownerId)) {
            throw new CustomException(ErrorCode.STORE_ACCESS_DENIED);
        }

        return store;
    }

    private ChargeBonus findChargeBonusById(Long chargeBonusId) {
        return chargeBonusRepository.findById(chargeBonusId)
                .orElseThrow(() -> new CustomException(ErrorCode.CHARGE_BONUS_NOT_FOUND));
    }
}