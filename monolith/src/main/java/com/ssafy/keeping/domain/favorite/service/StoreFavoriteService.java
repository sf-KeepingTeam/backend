package com.ssafy.keeping.domain.favorite.service;

import com.ssafy.keeping.domain.favorite.dto.FavoriteCheckResponseDto;
import com.ssafy.keeping.domain.favorite.dto.SimpleFavoriteDto;
import com.ssafy.keeping.domain.favorite.dto.FavoriteToggleResponseDto;
import com.ssafy.keeping.domain.favorite.dto.StoreFavoriteResponseDto;
import com.ssafy.keeping.domain.favorite.dto.StoreFavoriteCountResponseDto;
import com.ssafy.keeping.domain.favorite.model.StoreFavorite;
import com.ssafy.keeping.domain.favorite.repository.StoreFavoriteRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class StoreFavoriteService {

    private final StoreFavoriteRepository storeFavoriteRepository;
    private final CustomerRepository customerRepository;
    private final StoreRepository storeRepository;
    private final OwnerRepository ownerRepository;

    @Transactional
    public FavoriteToggleResponseDto toggleFavorite(Long customerId, Long storeId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        Optional<StoreFavorite> existingFavorite =
                storeFavoriteRepository.findByCustomerAndStore(customer, store);

        if (existingFavorite.isPresent()) {
            StoreFavorite favorite = existingFavorite.get();

            if (favorite.isActive()) {
                favorite.cancel();
                return new FavoriteToggleResponseDto(
                        customerId,
                        storeId,
                        false,
                        favorite.getFavoriteId()
                );
            } else {
                favorite.reactivate();
                return new FavoriteToggleResponseDto(
                        customerId,
                        storeId,
                        true,
                        favorite.getFavoriteId()
                );
            }
        } else {
            StoreFavorite newFavorite = StoreFavorite.builder()
                    .customer(customer)
                    .store(store)
                    .active(true)
                    .build();

            StoreFavorite savedFavorite = storeFavoriteRepository.save(newFavorite);

            return new FavoriteToggleResponseDto(
                    customerId,
                    storeId,
                    true,
                    savedFavorite.getFavoriteId()
            );
        }
    }

    @Transactional(readOnly = true)
    public StoreFavoriteResponseDto getFavoriteStores(Long customerId, Pageable pageable) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Page<StoreFavorite> favoritePage =
                storeFavoriteRepository.findByCustomerAndActiveTrueOrderByFavoritedAtDesc(customer, pageable);

        Page<SimpleFavoriteDto> favoriteStores = favoritePage.map(sf -> new SimpleFavoriteDto(
                sf.getFavoriteId(),
                sf.getStore().getStoreId(),
                sf.getFavoritedAt()
        ));

        long totalCount = storeFavoriteRepository.countByCustomerAndActiveTrue(customer);

        return new StoreFavoriteResponseDto(
                customerId,
                totalCount,
                favoriteStores
        );
    }

    @Transactional(readOnly = true)
    public FavoriteCheckResponseDto checkFavoriteStatus(Long customerId, Long storeId) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Store store = storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        Optional<StoreFavorite> activeFavorite =
                storeFavoriteRepository.findByCustomerAndStoreAndActiveTrue(customer, store);

        return new FavoriteCheckResponseDto(
                customerId,
                storeId,
                activeFavorite.isPresent(),
                activeFavorite.map(StoreFavorite::getFavoriteId).orElse(null)
        );
    }

    @Transactional(readOnly = true)
    public StoreFavoriteCountResponseDto getStoreFavoriteCount(Long ownerId, Long storeId) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        Store store = storeRepository.findByStoreIdAndOwner(storeId, owner)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));

        long favoriteCount = storeFavoriteRepository.countByStoreAndActiveTrue(store);

        return new StoreFavoriteCountResponseDto(
                store.getStoreId(),
                store.getStoreName(),
                favoriteCount
        );
    }

}