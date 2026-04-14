package com.ssafy.keeping.domain.ocr.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BizLicenseOcrResponse {

    private final String bizNumber;   // 등록번호 → 569-19-01664 로 정규화
    private final String fullName;    // 성명
    private final String openDate;    // 개업연월일 → YYYY-MM-DD 정규화
    private final Double confidence;  // 평균 신뢰도(선택)

}