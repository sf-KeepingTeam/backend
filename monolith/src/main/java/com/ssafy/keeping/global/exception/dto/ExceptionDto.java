package com.ssafy.keeping.global.exception.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@AllArgsConstructor
@Builder
public class ExceptionDto {
  private final LocalDateTime timestamp;
  private final int status;
  private final String error;
  private final String message;
  private final String path;
}
