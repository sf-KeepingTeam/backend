package com.ssafy.keeping.global.cache;

import com.ssafy.keeping.domain.user.customer.model.Customer;
import com.ssafy.keeping.domain.user.customer.repository.CustomerRepository;
import com.ssafy.keeping.domain.wallet.constant.WalletType;
import com.ssafy.keeping.domain.wallet.model.Wallet;
import com.ssafy.keeping.domain.wallet.repository.WalletRepository;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * 정적 참조 데이터 캐싱 전담 컴포넌트.
 *
 * <p>WalletService의 private validCustomer()는 Spring AOP 프록시를 우회하므로 @Cacheable 부착 불가.
 * 별도 @Component로 분리해 read-only 경로에서 호출한다.
 *
 * <p>write 경로(doSharePoints, doReclaimPoints 등)는 기존 WalletService.validCustomer()를 그대로 사용.
 *
 * <p>TTL 내 DB 변경은 캐시에 반영되지 않음 (customer: 600s, wallet: 600s).
 * 회원탈퇴·지갑 생성 직후 일시적 불일치 허용.
 */
@Component
@RequiredArgsConstructor
public class RefDataCacheService {

  private final CustomerRepository customerRepository;
  private final WalletRepository walletRepository;

  /**
   * 고객 존재 확인 및 조회 (캐시됨).
   *
   * <p>Caffeine provider: 인메모리 객체 그대로 반환 (직렬화 없음, LAZY 프록시 안전).
   * Redis provider: GenericJackson2JsonRedisSerializer 사용 — 엔티티 직렬화 전 검증 필요.
   */
  @Cacheable(value = "customer", key = "#customerId")
  public Customer findCustomerById(Long customerId) {
    return customerRepository
        .findById(customerId)
        .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
  }

  /**
   * 고객의 지갑 조회 (캐시됨).
   *
   * <p>캐시 키: "{customerId}:{walletType}" — 개인/모임 지갑 구분.
   * Caffeine provider에서만 LAZY 프록시 안전. Redis 사용 시 직렬화 검증 필요.
   */
  @Cacheable(value = "wallet", key = "#customerId + ':' + #walletType.name()")
  public Wallet findWalletByCustomerIdAndType(Long customerId, WalletType walletType) {
    return walletRepository
        .findByCustomerIdAndWalletType(customerId, walletType)
        .orElseThrow(() -> new CustomException(ErrorCode.WALLET_NOT_FOUND));
  }
}
