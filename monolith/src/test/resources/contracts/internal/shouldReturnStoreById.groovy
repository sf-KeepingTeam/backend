package contracts.internal

import org.springframework.cloud.contract.spec.Contract

Contract.make {
    description "매장 ID로 매장 정보를 조회한다"

    request {
        method GET()
        url "/internal/stores/1"
        headers {
            header("X-Internal-Auth", "internal-service-token-12345")
        }
    }

    response {
        status OK()
        headers {
            contentType(applicationJson())
        }
        body([
            storeId: 1,
            storeName: "테스트 매장",
            ownerId: 100,
            taxIdNumber: "123-45-67890",
            address: "서울시 강남구",
            active: true
        ])
    }
}
