package com.ssafy.keeping.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class PasswordConfig {

    /**
     *
     * @return
     *  - Argon2PasswordEncoder(saltLen, hashLen, parallelism, memoryKb, iterations)
     *  - saltLen : salt 길이(바이트)
     *  - hashLen : 해시 길이(바이트)
     *  - parallelism : 병렬도(lanes)
     *  - memoryKb : 메모리(KB)
     *  - iterations : 반복 횟수
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new Argon2PasswordEncoder(16, 32, 1, 1 << 13, 3);
    }

}