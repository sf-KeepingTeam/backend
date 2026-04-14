package com.ssafy.keeping.domain.user.dto;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Builder
public class UserProfile {

    private Long userId;
    private UserRole role;
    private String name;
    private String email;
    private String phoneNumber;
    private String imgUrl;
    private Gender gender;
    private LocalDate birth;
    private AuthProvider providerType;
    private LocalDateTime createdAt;

    public static UserProfile fromCustomer(Customer customer) {
        return UserProfile.builder()
                .userId(customer.getCustomerId())
                .role(UserRole.CUSTOMER)
                .name(customer.getName())
                .email(customer.getEmail())
                .phoneNumber(customer.getPhoneNumber())
                .imgUrl(customer.getImgUrl())
                .gender(customer.getGender())
                .birth(customer.getBirth())
                .providerType(customer.getProviderType())
                .createdAt(customer.getCreatedAt())
                .build();
    }

    public static UserProfile fromOwner(Owner owner) {
        return UserProfile.builder()
                .userId(owner.getOwnerId())
                .role(UserRole.OWNER)
                .name(owner.getName())
                .email(owner.getEmail())
                .phoneNumber(owner.getPhoneNumber())
                .imgUrl(owner.getImgUrl())
                .gender(owner.getGender())
                .birth(owner.getBirth())
                .providerType(owner.getProviderType())
                .createdAt(owner.getCreatedAt())
                .build();
    }
}
