package com.ssafy.keeping.domain.store.service;

import com.ssafy.keeping.domain.internal.webhook.QrServiceWebhookPublisher;
import com.ssafy.keeping.domain.store.constant.StoreStatus;
import com.ssafy.keeping.domain.store.dto.StoreEditRequestDto;
import com.ssafy.keeping.domain.store.dto.StorePublicDto;
import com.ssafy.keeping.domain.store.dto.StoreRequestDto;
import com.ssafy.keeping.domain.store.dto.StoreResponseDto;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.domain.wallet.repository.WalletStoreBalanceRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.s3.service.ImageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class StoreService {
    private final StoreRepository storeRepository;
    private final OwnerRepository ownerRepository;
    private final WalletStoreBalanceRepository balanceRepository;
    private final ImageService imageService;
    private final QrServiceWebhookPublisher webhookPublisher;

    /**
     * 가게 생성 (간소화 버전 - 외부 API merchantId 생성 제거)
     */
    @Transactional
    public StoreResponseDto createStore(Long ownerId, StoreRequestDto requestDto) {
        Owner owner = validOwner(ownerId);
        String taxIdNumber = requestDto.getTaxIdNumber();
        String address = requestDto.getAddress();

        boolean exists = storeRepository.existsByTaxIdNumberAndAddress(taxIdNumber, address);
        if (exists) {
            throw new CustomException(ErrorCode.STORE_ALREADY_EXISTS);
        }

        // 이미지 파일 업로드
        String imgUrl = imageService.uploadImage((requestDto.getImgFile()), "store");
        if(imgUrl == null || imgUrl.isEmpty()) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }

        // 가게 생성 (merchantId는 자동 생성되는 storeId를 사용하거나 제거)
        Store store = Store.builder()
                .owner(owner)
                .taxIdNumber(requestDto.getTaxIdNumber())
                .storeName(requestDto.getStoreName())
                .address(requestDto.getAddress())
                .phoneNumber(requestDto.getPhoneNumber())
                .category(requestDto.getCategory())
                .description(requestDto.getDescription())
                .storeStatus(StoreStatus.ACTIVE)
                .imgUrl(imgUrl)
                .build();

        store = storeRepository.save(store);
        log.info("가게 등록 완료 - storeId: {}, storeName: {}, ownerId: {}",
                store.getStoreId(), store.getStoreName(), ownerId);

        // QR Service 캐시 갱신 Push (비동기)
        webhookPublisher.publishStoreUpdate(store);

        return StoreResponseDto.fromEntity(store);
    }

    @Transactional
    public StoreResponseDto editStore(Long storeId, Long ownerId, StoreEditRequestDto requestDto) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);
        if (!store.getOwner().getOwnerId().equals(owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);

        if (!Objects.equals(store.getStoreStatus(), StoreStatus.ACTIVE)) {
            throw new CustomException(ErrorCode.STORE_INVALID);
        }

        String editImgUrl = imageService.updateProfileImage(store.getImgUrl(), requestDto.getImgFile());
        if(editImgUrl == null || editImgUrl.isEmpty()) {
            throw new CustomException(ErrorCode.IMAGE_UPDATE_ERROR);
        }

        String taxId = store.getTaxIdNumber();
        String address = requestDto.getAddress();

        boolean exists = storeRepository.existsByTaxIdNumberAndAddress(taxId, address);
        if (exists) {
            throw new CustomException(ErrorCode.STORE_ALREADY_EXISTS);
        }

        store.patchStore(requestDto, editImgUrl);

        Store saved = storeRepository.save(store);

        // QR Service 캐시 갱신 Push (비동기)
        webhookPublisher.publishStoreUpdate(saved);

        return StoreResponseDto.fromEntity(saved);
    }

    @Transactional
    public StoreResponseDto deleteStore(Long storeId, Long ownerId) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);

        if (!store.getOwner().getOwnerId().equals(owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);

        boolean hasPositive = balanceRepository
                .existsPositiveBalanceForStoreWithLock(storeId);

        StoreStatus status = hasPositive ? StoreStatus.SUSPENDED : StoreStatus.DELETED;
        store.deleteStore(status);

        Store saved = storeRepository.save(store);

        // QR Service 캐시 삭제 Push (비동기)
        webhookPublisher.publishStoreDelete(storeId);

        return StoreResponseDto.fromEntity(saved);
    }

    /**
     * 전체 가게 조회
     */
    public List<StorePublicDto> getAllStore() {
        return storeRepository.findPublicAllApprovedStore(StoreStatus.ACTIVE);
    }

    /**
     * 가게 상세 조회
     */
    public StorePublicDto getStoreByStoreId(Long storeId) {
        return storeRepository.findPublicById(storeId, StoreStatus.ACTIVE).orElseThrow(
                () -> new CustomException(ErrorCode.STORE_NOT_FOUND));
    }

    /**
     * 카테고리별 가게 조회
     */
    public List<StorePublicDto> getAllStoreByCategory(String categoryName) {
        String category = categoryName == null ? "" : categoryName.trim();
        if (category.isEmpty()) {
            return storeRepository.findPublicAllApprovedStore(StoreStatus.ACTIVE);
        }

        return storeRepository.findPublicAllByCategory(category, StoreStatus.ACTIVE);
    }

    /**
     * 가게명으로 검색
     */
    public List<StorePublicDto> getStoreByStoreName(String storeName) {
        String name = storeName == null ? "" : storeName.trim();
        if (name.isEmpty()) {
            return storeRepository.findPublicAllApprovedStore(StoreStatus.ACTIVE);
        }
        name = name.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");

        return storeRepository.findPublicAllSimilarityByName(name, StoreStatus.ACTIVE);
    }

    private Owner validOwner(Long ownerId) {
        return ownerRepository.findById(ownerId).orElseThrow(
                () -> new CustomException(ErrorCode.OWNER_NOT_FOUND)
        );
    }

    private Store validStore(Long storeId) {
        return storeRepository.findById(storeId).orElseThrow(
                () -> new CustomException(ErrorCode.STORE_NOT_FOUND)
        );
    }

    /**
     * 점주의 모든 매장 조회
     */
    public List<StoreResponseDto> getMyStores(Long ownerId) {
        validOwner(ownerId);

        List<Store> stores = storeRepository.findByOwnerOwnerIdAndDeletedAtIsNull(ownerId);

        return stores.stream()
                .map(StoreResponseDto::fromEntity)
                .toList();
    }
}
