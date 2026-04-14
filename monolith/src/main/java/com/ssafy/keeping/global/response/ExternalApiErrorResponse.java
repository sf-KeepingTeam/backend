package com.ssafy.keeping.global.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExternalApiErrorResponse {
    private String code;
    private String message;
    private String description;
}
