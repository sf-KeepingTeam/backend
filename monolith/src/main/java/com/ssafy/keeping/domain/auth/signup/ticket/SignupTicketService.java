package com.ssafy.keeping.domain.auth.signup.ticket;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SignupTicketService {

    private static final String PREFIX = "signup:ticket:";
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    /**
     * 가입이 필요한 사용자가 생겼을 때 “티켓”을 발급하고 Redis에 저장하는 메서드
     * @param payload
     * @param ttl
     * @return ticket
     */
    public String create(SignupTicketPayload payload, Duration ttl) {
        String ticket = UUID.randomUUID().toString();
        String key = PREFIX + ticket;

        try {
            String json = objectMapper.writeValueAsString(payload);
            redis.opsForValue().set(key, json, ttl); // Redis String 타입으로 저장 (SET)
            return ticket;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create signup ticket", e);
        }
    }

    /**
     * 프론트가 회원가입 API를 호출할 때 ticket을 넘겨주면,
     * 그 ticket으로 Redis에서 payload를 꺼내오는 메서드
     * @param ticket
     * @return payload
     */
    public SignupTicketPayload getPayload(String ticket) {
        String key = PREFIX + ticket;

        String json = redis.opsForValue().get(key); // Redis에서 조회(GET)
        if (json == null) return null; // TODO: ticket 존재 안 하면 null 말고 명확한 예외

        try {
            return objectMapper.readValue(json, SignupTicketPayload.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse signup ticket", e);
        }
    }

    /**
     * 티켓 무효화(삭제) 메서드
     * @param ticket
     */
    public void invalidate(String ticket) {
        redis.delete(PREFIX + ticket);
    }

}
