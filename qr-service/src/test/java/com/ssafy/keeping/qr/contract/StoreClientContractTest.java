package com.ssafy.keeping.qr.contract;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.ssafy.keeping.qr.acl.StoreClient;
import com.ssafy.keeping.qr.acl.dto.StoreResponse;
import org.junit.jupiter.api.*;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract Test - StoreClient
 *
 * 이 테스트의 Stub 정의는 Monolith의 Contract와 동일해야 합니다.
 * Monolith가 Contract를 변경하면 이 테스트의 Stub도 함께 변경해야 합니다.
 *
 * Contract 파일 위치: backend/src/test/resources/contracts/internal/shouldReturnStoreById.groovy
 *
 * [계약 위반 감지 흐름]
 * 1. Monolith가 API 응답 변경
 * 2. Monolith Contract Test 실패 → 수정 필요
 * 3. 수정 후 QR Service 테스트도 함께 수정
 * 4. 둘 다 통과해야 배포 가능!
 */
class StoreClientContractTest {

    static WireMockServer wireMockServer;
    StoreClient storeClient;

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(18080);
        wireMockServer.start();
        WireMock.configureFor("localhost", 18080);
    }

    @AfterAll
    static void stopWireMock() {
        wireMockServer.stop();
    }

    @BeforeEach
    void setup() {
        wireMockServer.resetAll();

        RestTemplate restTemplate = new RestTemplateBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .build();

        storeClient = new StoreClient(restTemplate);
        ReflectionTestUtils.setField(storeClient, "monolithUrl", "http://localhost:18080");
        ReflectionTestUtils.setField(storeClient, "internalAuthToken", "internal-service-token-12345");
    }

    /**
     * Contract: shouldReturnStoreById.groovy 와 동일한 Stub
     *
     * Monolith Contract 변경 시 이 Stub도 함께 변경 필요!
     */
    @Test
    @DisplayName("매장 정보 조회 - Contract 검증")
    void shouldReturnStoreById() {
        // Given: Contract와 동일한 Stub (Monolith Contract 변경 시 여기도 변경!)
        stubFor(get(urlEqualTo("/internal/stores/1"))
                .withHeader("X-Internal-Auth", equalTo("internal-service-token-12345"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "storeId": 1,
                                "storeName": "테스트 매장",
                                "ownerId": 100,
                                "taxIdNumber": "123-45-67890",
                                "address": "서울시 강남구",
                                "active": true
                            }
                            """)));

        // When
        Optional<StoreResponse> result = storeClient.getStore(1L);

        // Then: QR Service의 StoreResponse DTO와 매핑되는지 검증
        assertThat(result).isPresent();
        assertThat(result.get().getStoreId()).isEqualTo(1L);
        assertThat(result.get().getStoreName()).isEqualTo("테스트 매장");
        assertThat(result.get().getOwnerId()).isEqualTo(100L);
        assertThat(result.get().getAddress()).isEqualTo("서울시 강남구");
    }
}
