package com.ssafy.keeping.domain.store.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatisticsRequestDto {

    private LocalDate date;

    private LocalDate startDate;

    private LocalDate endDate;
}