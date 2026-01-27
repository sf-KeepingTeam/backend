package com.ssafy.keeping.domain.menuCategory.service;

import com.ssafy.keeping.domain.menuCategory.dto.MenuCategoryEditRequestDto;
import com.ssafy.keeping.domain.menuCategory.dto.MenuCategoryRequestDto;
import com.ssafy.keeping.domain.menuCategory.dto.MenuCategoryResponseDto;
import com.ssafy.keeping.domain.menuCategory.model.MenuCategory;
import com.ssafy.keeping.domain.menuCategory.repository.MenuCategoryRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Transactional
public class MenuCategoryService {

    private final StoreRepository storeRepository;
    private final MenuCategoryRepository menuCategoryRepository;
    private final OwnerRepository ownerRepository;

    public MenuCategoryResponseDto createMenuCategory(Long ownerId, Long storeId, MenuCategoryRequestDto requestDto) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);
        ensureOwnership(owner, store);

        Long parentId = requestDto.getParentId();

        MenuCategory parent = null;
        if (parentId != null) {
            parent = menuCategoryRepository.findById(parentId)
                    .orElseThrow(() -> new CustomException(ErrorCode.MENU_CATEGORY_NOT_FOUND));

            if (!Objects.equals(storeId, parent.getStore().getStoreId()))
                throw new CustomException(ErrorCode.STORE_NOT_MATCH);
        }

        if (menuCategoryRepository.existsDuplicationName(storeId, parentId, requestDto.getCategoryName(), null))
            throw new CustomException(ErrorCode.DUPLICATE_RESOURCE);

        int order = menuCategoryRepository.nextOrder(storeId, parentId);

        MenuCategory saved = menuCategoryRepository.save(
                MenuCategory.builder()
                        .categoryName(requestDto.getCategoryName())
                        .store(store)
                        .parent(parent)
                        .displayOrder(order)
                        .build()
        );

        return toDto(saved);
    }

    @Transactional(readOnly = true)
    public List<MenuCategoryResponseDto> getAllMajorCategory(Long storeId) {
        validStore(storeId);
        return menuCategoryRepository.findAllMajorCategoryByStoreId(storeId);
    }

    public MenuCategoryResponseDto editMenuCategory(Long ownerId, Long storeId, Long categoryId, MenuCategoryEditRequestDto requestDto) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);
        ensureOwnership(owner, store);

        MenuCategory category = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new CustomException(ErrorCode.MENU_CATEGORY_NOT_FOUND));

        if (!Objects.equals(storeId, category.getStore().getStoreId()))
            throw new CustomException(ErrorCode.STORE_NOT_MATCH);

        Long newParentId = requestDto.getParentId();

        if (menuCategoryRepository.existsDuplicationName(storeId, newParentId, requestDto.getCategoryName(), categoryId))
            throw new CustomException(ErrorCode.DUPLICATE_RESOURCE);

        MenuCategory newParent = null;
        if (newParentId != null) {
            newParent = menuCategoryRepository.findById(newParentId)
                    .orElseThrow(() -> new CustomException(ErrorCode.MENU_CATEGORY_NOT_FOUND));
            if (!Objects.equals(storeId, newParent.getStore().getStoreId()))
                throw new CustomException(ErrorCode.STORE_NOT_MATCH);
        }

        Long beforeParentId = category.getParent() == null ? null : category.getParent().getCategoryId();
        if (!Objects.equals(newParentId, beforeParentId)) {
            int nextOrder = menuCategoryRepository.nextOrder(storeId, newParentId);
            category.changeOrder(nextOrder);
        }

        category.changeNameAndParent(requestDto.getCategoryName(), newParent);

        return toDto(category);
    }

    public void deleteMenuCategory(Long ownerId, Long storeId, Long categoryId) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);
        ensureOwnership(owner, store);

        MenuCategory category = menuCategoryRepository.findById(categoryId)
                .orElseThrow(() -> new CustomException(ErrorCode.MENU_CATEGORY_NOT_FOUND));

        if (!Objects.equals(storeId, category.getStore().getStoreId()))
            throw new CustomException(ErrorCode.STORE_NOT_MATCH);

        if (menuCategoryRepository.hasChildren(storeId, categoryId))
            throw new CustomException(ErrorCode.MENU_CATEGORY_HAS_CHILDREN);

        menuCategoryRepository.delete(category);
    }

    // ===== helpers =====

    private Owner validOwner(Long ownerId) {
        return ownerRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.OWNER_NOT_FOUND));
    }

    private Store validStore(Long storeId) {
        return storeRepository.findById(storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.STORE_NOT_FOUND));
    }

    private void ensureOwnership(Owner owner, Store store) {
        if (!Objects.equals(store.getOwnerId(), owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);
    }

    private MenuCategoryResponseDto toDto(MenuCategory mc) {
        Long pid = mc.getParent() == null ? null : mc.getParent().getCategoryId();
        return new MenuCategoryResponseDto(
                mc.getCategoryId(),
                mc.getStore().getStoreId(),
                pid,
                mc.getCategoryName(),
                mc.getDisplayOrder(),
                mc.getCreatedAt()
        );
    }
}