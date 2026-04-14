package com.ssafy.keeping.domain.user.owner.dto;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@Builder
public class OwnerRegisterResponse {
    private Long ownerId;
    private String providerId;
    private AuthProvider providerType;
    private String name;
    private String email;
    private String phoneNumber;
    private LocalDate birth;
    private Gender gender;
    private String imgUrl;

    public static OwnerRegisterResponse register(Owner owner) {
        return OwnerRegisterResponse.builder()
                .ownerId(owner.getOwnerId())
                .providerId(owner.getProviderId())
                .providerType(owner.getProviderType())
                .phoneNumber(owner.getPhoneNumber())
                .name(owner.getName())
                .email(owner.getEmail())
                .gender(owner.getGender())
                .birth(owner.getBirth())
                .imgUrl(owner.getImgUrl())
                .build();
    }
}
