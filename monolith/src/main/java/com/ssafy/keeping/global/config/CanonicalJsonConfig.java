package com.ssafy.keeping.global.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CanonicalJsonConfig {

    @Bean("canonicalObjectMapper")
    public ObjectMapper canonicalObjectMapper() {
        ObjectMapper om = new ObjectMapper();
        // 날짜/시간 ISO-8601, 타임스탬프 금지
        om.registerModule(new JavaTimeModule());
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // null 필드 제외(불필요한 표현 차이 제거)
        om.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        // Map 키 정렬(알파벳 순)
        om.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        // 빈 객체 오류 방지(안전)
        om.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        return om;
    }
}