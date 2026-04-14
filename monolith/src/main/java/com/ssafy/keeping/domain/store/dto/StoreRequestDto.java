package com.ssafy.keeping.domain.store.dto;


import jakarta.validation.constraints.Pattern;
import org.springframework.lang.Nullable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

@Data
@AllArgsConstructor
public class StoreRequestDto {

    @NotBlank
    @Pattern(regexp = "^[0-9]{3}-[0-9]{2}-[0-9]{5}$", message = "사업자 등록번호 형식은 XXX-XX-XXXXX 여야 합니다.")
    private String taxIdNumber;

    @NotBlank
    private String storeName;

    @NotBlank
    private String address;

    private String phoneNumber;

    @NotBlank
    private String category;

    private String description;

    //TODO: 파일서버 구축 후 수정
    @Nullable
    private MultipartFile imgFile;
}
