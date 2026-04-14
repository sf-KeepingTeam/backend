package com.ssafy.keeping.domain.menu.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Null;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MenuRequestDto {
    @NotBlank
    private String menuName;
    @NotNull
    private Long categoryId;
    @Min(1000)
    private int price;
    private String description;
    @Nullable
    private MultipartFile imgFile;
}
