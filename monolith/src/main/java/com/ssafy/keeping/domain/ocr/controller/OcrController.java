package com.ssafy.keeping.domain.ocr.controller;

import com.ssafy.keeping.domain.ocr.dto.BizLicenseOcrResponse;

import com.ssafy.keeping.domain.ocr.dto.MenuOcrResponse;
import com.ssafy.keeping.domain.ocr.service.BizLicenseOcrService;
import com.ssafy.keeping.domain.ocr.service.MenuOcrService;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/ocr")
public class OcrController {

    private final BizLicenseOcrService bizLicenseOcrService;
    private final MenuOcrService menuOcrService;

    @PostMapping(value = "/biz-license" , consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<BizLicenseOcrResponse>> recognizeBizLicense(
            @RequestPart("file")MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.OCR_FILE_REQUIRED);
        }

        // 이미지 타입 제한
        String ct = file.getContentType();
        if (ct == null || !(ct.equals("image/jpeg") || ct.equals("image/png") || ct.equals("image/jpg"))) {
            throw new CustomException(ErrorCode.OCR_FILE_TYPE_NOT_ALLOWED);
        }
        // 용량 제한(예: 10MB)
        long maxBytes = 10L * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new CustomException(ErrorCode.OCR_FILE_TOO_LARGE);
        }

        BizLicenseOcrResponse data = bizLicenseOcrService.recognize(file);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        "사업자등록증 OCR 결과가 생성되었습니다",
                        HttpStatus.CREATED.value(),
                        data
                ));
    }

    @PostMapping(value = "/menu" , consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MenuOcrResponse>> recognizeMenu(
            @RequestPart("file") MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {
            throw new CustomException(ErrorCode.OCR_FILE_REQUIRED);
        }
        String ct = file.getContentType();
        if (ct == null || !(ct.equals("image/jpeg") || ct.equals("image/png") || ct.equals("image/jpg"))) {
            throw new CustomException(ErrorCode.OCR_FILE_TYPE_NOT_ALLOWED);
        }
        long maxBytes = 10L * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new CustomException(ErrorCode.OCR_FILE_TOO_LARGE);
        }

        MenuOcrResponse data = menuOcrService.recognize(file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("메뉴판 OCR 결과가 생성되었습니다", HttpStatus.CREATED.value(), data));
    }
}
