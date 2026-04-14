package com.ssafy.keeping.domain.user.customer.service;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.signup.dto.CustomerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketPayload;
import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.user.customer.dto.CustomerProfileResponse;
import com.ssafy.keeping.domain.user.customer.dto.CustomerProfileUpdateRequest;
import com.ssafy.keeping.domain.user.dto.ProfileUploadResponse;
import com.ssafy.keeping.global.s3.service.ImageService;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;


@Service
@RequiredArgsConstructor
@Slf4j
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final ImageService imageService;

    /**
     * 고객 생성
     * @param request
     * @param payload
     * @return
     */
    @Transactional
    public Customer registerCustomer(CustomerSignupRequest request, SignupTicketPayload payload) {

        Customer customer = Customer.builder()
                .providerType(payload.providerType())
                .providerId(payload.providerId())
                .name(request.name())
                .email(request.email())
                .gender(request.gender())
                .birth(request.birth())
                .imgUrl(payload.profileUrl())
                .phoneNumber(request.phoneNumber())
                .build();

        return customerRepository.save(customer);
    }

    /**
     * 소셜 로그인 제공자 타입과 제공자 ID로 고객 조회
     */
    public Optional<Customer> findByProviderTypeAndProviderId(AuthProvider providerType, String providerId) {
        return customerRepository.findByProviderTypeAndProviderIdAndDeletedAtIsNull(providerType, providerId);
    }

    /**
     * 프로필 이미지 변경
     */
    @Transactional
    public ProfileUploadResponse uploadProfileImage(Long customerId, MultipartFile newImage) {
        String oldImgUrl = customerRepository.findImageUrlByCustomerId(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.BAD_REQUEST));

        try {
            String newImgUrl = imageService.updateProfileImage(oldImgUrl, newImage);
            customerRepository.updateImageUrl(customerId, newImgUrl);

            log.info("사용자 {} 프로필 이미지 업데이트: {}", customerId, newImgUrl);
            return ProfileUploadResponse.builder()
                    .newImgUrl(newImgUrl)
                    .build();

        } catch (Exception e) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }
    }

    /**
     * 내 프로필 조회
     */
    public CustomerProfileResponse getMyProfile(Long customerId) {
        Customer customer = validCustomer(customerId);

        log.info("고객 프로필 조회 - 고객ID: {}", customerId);
        return CustomerProfileResponse.from(customer);
    }

    /**
     * 내 프로필 수정
     */
    @Transactional
    public CustomerProfileResponse updateMyProfile(Long customerId, CustomerProfileUpdateRequest request) {
        Customer customer = validCustomer(customerId);

        try {
            customerRepository.updateCustomerProfile(customerId, request.getName(), request.getPhoneNumber());

            Customer updatedCustomer = validCustomer(customerId);

            log.info("고객 프로필 수정 완료 - 고객ID: {}, 이름: {}, 전화번호: {}",
                     customerId, request.getName(), request.getPhoneNumber());

            return CustomerProfileResponse.from(updatedCustomer);

        } catch (Exception e) {
            log.error("고객 프로필 수정 실패 - 고객ID: {}", customerId, e);
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }
    }

    public Customer validCustomer(Long customerId) {
        return customerRepository.findByCustomerIdAndDeletedAtIsNull(customerId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }
}
