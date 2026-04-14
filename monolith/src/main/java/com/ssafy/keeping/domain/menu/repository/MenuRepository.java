package com.ssafy.keeping.domain.menu.repository;

import com.ssafy.keeping.domain.menu.dto.MenuResponseDto;
import com.ssafy.keeping.domain.menu.model.Menu;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MenuRepository extends JpaRepository<Menu, Long> {
    @Query(value = """
        select COALESCE(MAX(m.display_order), -1) + 1
        from menus m
        where m.store_id = :storeId
          and m.category_id = :categoryId
        """, nativeQuery = true)
    int nextOrderIncludingDeleted(@Param("storeId") Long storeId,
                                  @Param("categoryId") Long categoryId);

    Optional<Menu> findByMenuIdAndStore_StoreId(Long menuId, Long storeId);

    // 1. 손님용 (공개된 메뉴만 조회)
    @Query("""
        select new com.ssafy.keeping.domain.menu.dto.MenuResponseDto(
            m.menuId, m.store.storeId, m.menuName, m.category.categoryId,
            m.category.categoryName, m.displayOrder, m.soldOut,
            m.imgUrl, m.description, m.price
        )
        from Menu m
        where m.store.storeId = :storeId
          and m.active = true
          and m.deletedAt is null
    """)
    List<MenuResponseDto> findActiveMenusByStoreId(@Param("storeId") Long storeId);

    // 2. 사장님용 (비공개 포함 전체 조회)
    @Query("""
        select new com.ssafy.keeping.domain.menu.dto.MenuResponseDto(
            m.menuId, m.store.storeId, m.menuName, m.category.categoryId,
            m.category.categoryName, m.displayOrder, m.soldOut,
            m.imgUrl, m.description, m.price
        )
        from Menu m
        where m.store.storeId = :storeId
          and m.deletedAt is null
    """)
    List<MenuResponseDto> findAllMenusByStoreId(@Param("storeId") Long storeId);

    @Query("""
    select new com.ssafy.keeping.domain.menu.dto.MenuResponseDto(
    m.menuId, m.store.storeId, m.menuName, m.category.categoryId,
    m.category.categoryName, m.displayOrder, m.soldOut,
    m.imgUrl, m.description, m.price
    )
    from Menu m
    where m.category.categoryId = :categoryId
    and m.active = true  
    and m.deletedAt is null
    """)
    List<MenuResponseDto> findAllMenusByCategoryId(@Param("categoryId") Long categoryId);

    @Query("""
    select (count(m) > 0) from Menu m
    where m.store.storeId = :storeId
    and lower(m.menuName) = lower(:name)
    """)
    boolean existsDuplicationName(@Param("storeId") Long storeId,
                                  @Param("name") String name);

    int deleteAllByStore_StoreId(Long storeId);

    /** 전체 활성 메뉴 조회 (Cache Warming용) */
    @Query("""
    select m from Menu m
    where m.active = true
      and m.deletedAt is null
    """)
    List<Menu> findAllActiveMenus();
}
