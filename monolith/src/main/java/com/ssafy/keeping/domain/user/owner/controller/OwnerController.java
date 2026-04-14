package com.ssafy.keeping.domain.user.owner.controller;

import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.user.dto.ProfileUploadResponse;
import com.ssafy.keeping.domain.user.owner.dto.OwnerProfileResponse;
import com.ssafy.keeping.domain.user.owner.service.OwnerService;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/owners")
@RequiredArgsConstructor
public class OwnerController {

    private final OwnerService ownerService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<OwnerProfileResponse>> getMyProfile(@AuthenticationPrincipal UserPrincipal principal) {
        Long ownerId = principal.id();
        OwnerProfileResponse response = ownerService.getMyProfile(ownerId);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success("프로필 조회 성공", HttpStatus.OK.value(), response));
    }

    /**
     * 프로필 이미지 수정
     */
    @PostMapping(value = "/{ownerId}/profile-image/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<ProfileUploadResponse>> uploadProfileImage(@PathVariable Long ownerId,
                                                                                 @RequestParam("file") MultipartFile file) {
        ProfileUploadResponse response = ownerService.uploadProfileImage(ownerId, file);
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.success("성공적 변경", HttpStatus.OK.value(), response));
    }

}
