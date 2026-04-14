package com.ssafy.keeping.domain.menucategory.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MenuCategoryRequestDto {
    @NotBlank
    public String categoryName;
    public Long parentId; // 세부 카테고리로 입력할 경우 기존에 있는 카테고리 id 아래로 넣는다.
}
