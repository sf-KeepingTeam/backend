package com.ssafy.keeping.domain.group.dto;

import com.ssafy.keeping.domain.group.constant.RequestStatus;

public record AddRequestResponseDto(
   Long groupAddRequestId, String name, RequestStatus status
) {}
