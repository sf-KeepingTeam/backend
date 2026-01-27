package com.ssafy.keeping.domain.menu.service;

import com.ssafy.keeping.domain.menu.dto.MenuEditRequestDto;
import com.ssafy.keeping.domain.menu.dto.MenuRequestDto;
import com.ssafy.keeping.domain.menu.dto.MenuResponseDto;
import com.ssafy.keeping.domain.menu.model.Menu;
import com.ssafy.keeping.domain.menu.repository.MenuRepository;
import com.ssafy.keeping.domain.menuCategory.model.MenuCategory;
import com.ssafy.keeping.domain.menuCategory.repository.MenuCategoryRepository;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.s3.service.ImageService;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@Transactional
@RequiredArgsConstructor
public class MenuService {
    private final MenuRepository menuRepository;
    private final StoreRepository storeRepository;
    private final OwnerRepository ownerRepository;
    private final MenuCategoryRepository menuCategoryRepository;

    private final ImageService imageService;

    /*
    * 권한 필요 없는 메서드
    * */
    public List<MenuResponseDto> getAllMenus(Long storeId) {
        storeRepository.findById(storeId).orElseThrow(
                () -> new CustomException(ErrorCode.STORE_NOT_FOUND)
        );

        return menuRepository.findActiveMenusByStoreId(storeId);
    }

    public List<MenuResponseDto> getAllMenusByCategory(Long categoryId) {
        return menuRepository.findAllMenusByCategoryId(categoryId);
    }

    /*
    * 가게 주인이 조작하는 service (권한 필요)
    * */
    public List<MenuResponseDto> getAllMenusForOwner(Long ownerId, Long storeId) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);

        if (!store.getOwnerId().equals(owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);

        return menuRepository.findAllMenusByStoreId(storeId);
    }

    public MenuResponseDto createMenu(Long ownerId, Long storeId, MenuRequestDto requestDto) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);

        if (!store.getOwnerId().equals(owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);

        Long categoryId = requestDto.getCategoryId();
        MenuCategory category = validMenuCategory(categoryId);

        if(!Objects.equals(storeId, category.getStore().getStoreId())){
            throw new CustomException(ErrorCode.STORE_NOT_MATCH);
        }

        if (menuRepository.existsDuplicationName(storeId,  requestDto.getMenuName()))
            throw new CustomException(ErrorCode.DUPLICATE_RESOURCE);

        // 이미지 url 수정
        String imgUrl = imageService.uploadImage(requestDto.getImgFile(), "menu");
        if(imgUrl == null || imgUrl.isEmpty()) {
            throw new CustomException(ErrorCode.IMAGE_UPLOAD_ERROR);
        }

        int order = menuRepository.nextOrderIncludingDeleted(storeId, categoryId);
        Menu saved = menuRepository.save(
                    Menu.builder()
                            .menuName(requestDto.getMenuName())
                            .store(store)
                            .category(category)
                            .price(requestDto.getPrice())
                            .description(requestDto.getDescription() == null ?
                                    "" : requestDto.getDescription())
                            .displayOrder(order)
                            .imgUrl(imgUrl)
                            .build()
                    );
        return new MenuResponseDto(
                saved.getMenuId(),
                saved.getStore().getStoreId(), saved.getMenuName(), saved.getCategory().getCategoryId(),
                saved.getCategory().getCategoryName(), saved.getDisplayOrder(), saved.isSoldOut(),
                saved.getImgUrl(), saved.getDescription(), saved.getPrice()
        );
    }

    public MenuResponseDto editMenu(Long ownerId, Long storeId, Long menuId, MenuEditRequestDto requestDto) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);

        if (!store.getOwnerId().equals(owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);

        Menu menu = validMenu(menuId, storeId);

        MenuCategory category = validMenuCategory(requestDto.getCategoryId());
        if (!category.getStore().getStoreId().equals(storeId))
            throw new CustomException(ErrorCode.STORE_NOT_MATCH);

        boolean changed = !menu.getCategory().getCategoryId().equals(requestDto.getCategoryId());
        int order = changed ? menuRepository.nextOrderIncludingDeleted(storeId, requestDto.getCategoryId())
                : menu.getDisplayOrder();
        if (changed) menu.changeCategory(category);

        String imgUrl = menu.getImgUrl();
        if (requestDto.getImgFile() != null && !requestDto.getImgFile().isEmpty()) {
            imgUrl = imageService.updateProfileImage(imgUrl, requestDto.getImgFile());
        }

        int price = requestDto.getPrice();
        String desc = Optional.ofNullable(requestDto.getDescription())
                .filter(s -> !s.isBlank()).orElse(menu.getDescription());
        String name = Optional.ofNullable(requestDto.getMenuName()).orElse(menu.getMenuName());

        menu.editMenu(name, imgUrl, price, desc, order);

        return new MenuResponseDto(
                menu.getMenuId(), storeId, menu.getMenuName(),
                menu.getCategory().getCategoryId(), menu.getCategory().getCategoryName(),
                menu.getDisplayOrder(), menu.isSoldOut(),
                menu.getImgUrl(), menu.getDescription(), menu.getPrice()
        );
    }

    public void deleteMenu(Long ownerId, Long storeId, Long menusId) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);

        if (!store.getOwnerId().equals(owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);

        Menu menu = validMenu(menusId, storeId);

        menuRepository.deleteById(menusId);
    }

    public void deleteAllMenu(Long ownerId, Long storeId) {
        Owner owner = validOwner(ownerId);
        Store store = validStore(storeId);

        if (!store.getOwnerId().equals(owner.getOwnerId()))
            throw new CustomException(ErrorCode.OWNER_NOT_MATCH);

        menuRepository.deleteAllByStore_StoreId(storeId);
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

    private MenuCategory validMenuCategory(Long categoryId) {
        return  menuCategoryRepository.findById(categoryId).orElseThrow(
                () -> new CustomException(ErrorCode.MENU_CATEGORY_NOT_FOUND)
        );
    }

    private Menu validMenu(Long menuId, Long storeId) {
        return menuRepository.findByMenuIdAndStore_StoreId(menuId, storeId)
                .orElseThrow(() -> new CustomException(ErrorCode.MENU_NOT_FOUND));
    }
}
