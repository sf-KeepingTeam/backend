package com.ssafy.keeping.qr.domain.intent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ApproveRequest {

    @NotBlank(message = "PIN은 필수입니다")
    @Pattern(regexp = "\\d{6}", message = "PIN은 숫자 6자리여야 합니다.")
    private String pin;
}
