package com.ssafy.keeping.contract;

import com.ssafy.keeping.domain.internal.controller.InternalStoreController;
import com.ssafy.keeping.domain.store.model.Store;
import com.ssafy.keeping.domain.store.repository.StoreRepository;
import com.ssafy.keeping.domain.user.owner.model.Owner;
import io.restassured.module.mockmvc.RestAssuredMockMvc;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.web.servlet.setup.StandaloneMockMvcBuilder;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Contract Test Base Class
 *
 * Spring Cloud Contract가 이 클래스를 상속받아 자동으로 테스트를 생성합니다.
 * Standalone MockMvc로 컨트롤러만 테스트 (Spring Context 로드 없음!)
 */
public abstract class BaseContractTest {

    @BeforeEach
    void setup() {
        // Mock Repository 생성
        StoreRepository storeRepository = Mockito.mock(StoreRepository.class);

        // Contract에 정의된 응답을 반환하도록 Mock 설정
        Owner mockOwner = Owner.builder()
                .ownerId(100L)
                .build();

        Store mockStore = Store.builder()
                .storeId(1L)
                .storeName("테스트 매장")
                .owner(mockOwner)
                .taxIdNumber("123-45-67890")
                .address("서울시 강남구")
                .build();

        when(storeRepository.findById(eq(1L))).thenReturn(Optional.of(mockStore));

        // Controller 직접 생성 (DI 대신)
        InternalStoreController controller = new InternalStoreController(storeRepository);

        // Standalone MockMvc 설정 (Spring Context 불필요!)
        StandaloneMockMvcBuilder mockMvcBuilder = MockMvcBuilders.standaloneSetup(controller);

        RestAssuredMockMvc.standaloneSetup(mockMvcBuilder);
    }
}
