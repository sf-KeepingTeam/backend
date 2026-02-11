package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.acl.dto.MenuResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;

/**
 * Anti-Corruption Layer
 * 모놀리스의 Menu 서비스를 HTTP로 호출
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MenuClient {

    private final RestTemplate restTemplate;

    @Value("${monolith.url}")
    private String monolithUrl;

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    /**
     * 메뉴 목록 일괄 조회
     */
    public List<MenuResponse> getMenus(List<Long> menuIds) {
        String url = monolithUrl + "/internal/menus/batch";

        try {
            HttpHeaders headers = createHeaders();
            headers.set("Content-Type", "application/json");

            BatchMenuRequest body = new BatchMenuRequest(menuIds);

            ResponseEntity<List<MenuResponse>> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<List<MenuResponse>>() {}
            );

            return response.getBody() != null ? response.getBody() : Collections.emptyList();

        } catch (Exception e) {
            log.error("Menu 서비스 호출 실패: menuIds={}, error={}", menuIds, e.getMessage());
            return Collections.emptyList();
        }
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Auth", internalAuthToken);
        return headers;
    }

    private record BatchMenuRequest(List<Long> menuIds) {}
}
