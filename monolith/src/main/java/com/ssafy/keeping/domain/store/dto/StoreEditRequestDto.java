package com.ssafy.keeping.domain.store.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.lang.Nullable;
import org.springframework.web.multipart.MultipartFile;

@Data
@AllArgsConstructor
public class StoreEditRequestDto {
    // TODO:// store edit을 어떤 필드가 필요한지는 추후 확정
    private String storeName;

    private String address;

    private String phoneNumber;
    //TODO: 파일서버 구축 후 수정
    @Nullable
    private MultipartFile imgFile;
}
