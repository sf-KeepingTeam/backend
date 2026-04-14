package com.ssafy.keeping.qr.domain.qr.repository;

import com.ssafy.keeping.qr.domain.qr.model.QrToken;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QrTokenRepository extends CrudRepository<QrToken, String> {

    Optional<QrToken> findByTokenId(String tokenId);

    Optional<QrToken> findByWalletId(Long walletId);
}
