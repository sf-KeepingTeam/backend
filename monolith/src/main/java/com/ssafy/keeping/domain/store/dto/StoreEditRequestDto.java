package com.ssafy.keeping.domain.store.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.lang.Nullable;
import org.springframework.web.multipart.MultipartFile;

@Data
@AllArgsConstructor
public class StoreEditRequestDto {
    private String storeName;

    private String address;

    private String phoneNumber;
    @Nullable
    private MultipartFile imgFile;
}
