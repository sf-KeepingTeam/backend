package com.ssafy.keeping.domain.group.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class GroupExpelRequestDto {
    @NotNull
    private Long targetCustomerId;
}
