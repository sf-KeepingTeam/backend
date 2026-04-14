package com.ssafy.keeping.domain.user.owner.dto;

import com.ssafy.keeping.domain.user.owner.model.Owner;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OwnerProfileResponse {

    private String name;
    private String phoneNumber;
    private String email;
    private String imgUrl;

    public static OwnerProfileResponse from(Owner owner) {
        return OwnerProfileResponse.builder()
                .name(owner.getName())
                .phoneNumber(owner.getPhoneNumber())
                .email(owner.getEmail())
                .imgUrl(owner.getImgUrl())
                .build();
    }
}