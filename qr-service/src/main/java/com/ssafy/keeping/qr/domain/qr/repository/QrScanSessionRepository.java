package com.ssafy.keeping.qr.domain.qr.repository;

import com.ssafy.keeping.qr.domain.qr.model.QrScanSession;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface QrScanSessionRepository extends CrudRepository<QrScanSession, String> {
    Optional<QrScanSession> findBySessionToken(String sessionToken);
}
