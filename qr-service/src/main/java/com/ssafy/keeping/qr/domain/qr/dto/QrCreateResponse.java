package com.ssafy.keeping.qr.domain.qr.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class QrCreateResponse {

  private String tokenId;
  private LocalDateTime expiresAt;
  private Integer ttlSeconds;

  public static QrCreateResponse from(String tokenId, LocalDateTime expiresAt, int ttl) {
    return QrCreateResponse.builder().tokenId(tokenId).expiresAt(expiresAt).ttlSeconds(ttl).build();
  }
}
