package com.ssafy.keeping.domain.internal.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class BatchMenuRequest {
  private List<Long> menuIds;
}
