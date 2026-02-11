package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Optional;

/**
 * Anti-Corruption Layer
 * 모놀리스의 Store 서비스를 HTTP로 호출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreClient {

    private final RestTemplate restTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * 매장 정보 조회
     */
    public Optional<StoreResponse> getStore(Long storeId) {
        String url = monolithUrl + "/internal/stores/" + storeId;

        try {
            HttpHeaders headers = createHeaders();

            ResponseEntity<StoreResponse> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    new HttpEntity<>(headers),
                    StoreResponse.class
            );

            return Optional.ofNullable(response.getBody());

        } catch (Exception e) {
            log.error("Store 서비스 호출 실패: storeId={}, error={}", storeId, e.getMessage());
            return Optional.empty();
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }
}
