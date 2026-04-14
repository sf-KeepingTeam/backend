package com.ssafy.keeping.domain.auth.signup.dto;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.Gender;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.owner.model.Owner;

import java.time.LocalDate;

public record RegisterResponse(
        Long userId,
        AuthProvider providerType,
        String name,
        String email,
        String phoneNumber,
        LocalDate birth,
        Gender gender,
        String imgUrl
) {
    public static RegisterResponse from(Customer customer) {
        return new RegisterResponse(
                customer.getCustomerId(),
                customer.getProviderType(),
                customer.getName(),
                customer.getEmail(),
                customer.getPhoneNumber(),
                customer.getBirth(),
                customer.getGender(),
                customer.getImgUrl()
        );
    }

    public static RegisterResponse from(Owner owner) {
        return new RegisterResponse(
                owner.getOwnerId(),
                owner.getProviderType(),
                owner.getName(),
                owner.getEmail(),
                owner.getPhoneNumber(),
                owner.getBirth(),
                owner.getGender(),
                owner.getImgUrl()
        );
    }
}