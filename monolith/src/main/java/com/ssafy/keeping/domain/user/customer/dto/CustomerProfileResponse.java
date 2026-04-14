package com.ssafy.keeping.domain.user.customer.dto;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerProfileResponse {

    private String name;
    private String phoneNumber;
    private String email;
    private String imgUrl;

    public static CustomerProfileResponse from(Customer customer) {
        return CustomerProfileResponse.builder()
                .name(customer.getName())
                .phoneNumber(customer.getPhoneNumber())
                .email(customer.getEmail())
                .imgUrl(customer.getImgUrl())
                .build();
    }
}