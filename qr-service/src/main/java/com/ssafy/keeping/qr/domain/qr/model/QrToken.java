package com.ssafy.keeping.qr.domain.qr.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class QrToken {

  private String tokenId;

  private Long walletId;

  private Long customerId;
  private Long bindStoreId;

  private LocalDateTime createdAt;
  private LocalDateTime expiresAt;

  private Long ttl;

  @JsonIgnore
  public boolean isExpired() {
    return LocalDateTime.now().isAfter(expiresAt);
  }
}
