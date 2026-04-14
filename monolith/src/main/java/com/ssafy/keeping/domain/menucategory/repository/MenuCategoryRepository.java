package com.ssafy.keeping.domain.menucategory.repository;

import com.ssafy.keeping.domain.menucategory.dto.MenuCategoryResponseDto;
import com.ssafy.keeping.domain.menucategory.model.MenuCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MenuCategoryRepository extends JpaRepository<MenuCategory, Long> {

    @Query("""
    select coalesce(max(c.displayOrder), -1) + 1
    from MenuCategory c
    where c.store.storeId = :storeId
    and ( (:parentId is null and c.parent is null) or c.parent.categoryId = :parentId )
    """)
    Integer nextOrder(@Param("storeId") Long storeId, @Param("parentId") Long parentId);


    @Query("""
    select new com.ssafy.keeping.domain.menucategory.dto.MenuCategoryResponseDto(
        c.categoryId, c.store.storeId, c.parent.categoryId, c.categoryName,
        c.displayOrder, c.createdAt
    )
    from MenuCategory c
    where c.store.storeId = :storeId
    and c.parent is null
    """)
    List<MenuCategoryResponseDto> findAllMajorCategoryByStoreId(@Param("storeId") Long storeId);

    @Query("""
    select (count(c) > 0) 
      from MenuCategory c
     where c.store.storeId = :storeId
       and ( (:parentId is null and c.parent is null) or c.parent.categoryId = :parentId )
       and lower(c.categoryName) = lower(:name)
       and (:categoryId is null or c.categoryId <> :categoryId)
    """)
    boolean existsDuplicationName(@Param("storeId") Long storeId,
                          @Param("parentId") Long parentId,
                          @Param("name") String name,
                          @Param("categoryId") Long categoryId);

    @Query("""
    select (count(c) > 0) from MenuCategory c
    where c.store.storeId = :storeId
    and ( (:categoryId is null and c.parent is null) or c.parent.categoryId = :categoryId )
    """)
    boolean hasChildren(@Param("storeId") Long storeId,
                          @Param("categoryId") Long categoryId);
}
