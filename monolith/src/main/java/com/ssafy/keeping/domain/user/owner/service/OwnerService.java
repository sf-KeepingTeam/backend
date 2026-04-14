package com.ssafy.keeping.domain.user.owner.service;

import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.signup.dto.OwnerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketPayload;
import com.ssafy.keeping.domain.user.dto.ProfileUploadResponse;
import com.ssafy.keeping.domain.user.owner.dto.OwnerProfileResponse;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import com.ssafy.keeping.domain.user.owner.repository.OwnerRepository;

import java.util.Optional;

import com.ssafy.keeping.global.s3.service.ImageService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;


@Service
@RequiredArgsConstructor
@Slf4j
public class OwnerService {

    private final OwnerRepository ownerRepository;
    private final ImageService imageService;

    @Transactional
    public Owner registerOwner(OwnerSignupRequest request, SignupTicketPayload payload) {

        Owner owner = Owner.builder()
                .providerType(payload.providerType())
                .providerId(payload.providerId())
                .name(request.name())
                .email(request.email())
                .gender(request.gender())
                .birth(request.birth())
                .imgUrl(payload.profileUrl())
                .phoneNumber(request.phoneNumber())
                .build();

        return ownerRepository.save(owner);
    }

    /**
     * 소셜 로그인 제공자 타입과 제공자 ID로 점주 조회
     */
    public Optional<Owner> findByProviderTypeAndProviderId(AuthProvider providerType, String providerId) {
        return ownerRepository.findByProviderTypeAndProviderIdAndDeletedAtIsNull(providerType, providerId);
    }

    public OwnerProfileResponse getMyProfile(Long ownerId) {
        Owner owner = ownerRepository.findById(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.BAD_REQUEST));
        return OwnerProfileResponse.from(owner);
    }

    /**
     * 프로필 이미지 변경
     */
    @Transactional
    public ProfileUploadResponse uploadProfileImage(Long ownerId, MultipartFile newImage) {
        String oldImgUrl = ownerRepository.findImageUrlByOwnerId(ownerId)
                .orElseThrow(() -> new CustomException(ErrorCode.BAD_REQUEST));

        try {
            String newImgUrl = imageService.updateProfileImage(oldImgUrl, newImage);
            ownerRepository.updateImageUrl(ownerId, newImgUrl);

            log.info("사용자 {} 프로필 이미지 업데이트: {}", ownerId, newImgUrl);
            return ProfileUploadResponse.builder()
                    .newImgUrl(newImgUrl)
                    .build();

        } catch (Exception e) {
            throw new CustomException(ErrorCode.BAD_REQUEST);
        }
    }

}
