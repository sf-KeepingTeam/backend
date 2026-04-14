package com.ssafy.keeping.domain.auth.token;

import com.ssafy.keeping.domain.auth.enums.UserRole;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

@Service
@RequiredArgsConstructor
public class AccessTokenService {

    private final JwtProperties props; // JWT 발급/검증에 필요한 설정 값 보관

    /**
     * Access Token(JWT)을 만들어 문자열로 반환하는 메서드
     * @param subject
     * @param role
     * @return Access Token(JWT)
     */
    public String issueAccessToken(String subject, UserRole role) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(props.accessTtlSeconds()); // 만료시간

        byte[] keyBytes = props.secret().getBytes(StandardCharsets.UTF_8);
        var key = Keys.hmacShaKeyFor(keyBytes); // HMAC-SHA 서명용 키

        return Jwts.builder()
                .issuer(props.issuer())
                .subject(subject)
                .claim("role", role.name())
                .issuedAt(Date.from(now)) // 발급 시간
                .expiration(Date.from(exp)) // 만료 시간
                .signWith(key) // 서명
                .compact(); // 문자열로 직렬화
    }

    /**
     * application.yaml 설정값 그대로 반환
     * @return Access Token(JWT) 유효시간
     */
    public long accessTtlSeconds() {
        return props.accessTtlSeconds();
    }

    /**
     * 성공하면 Claims를 주고, 실패하면 예외를 던짐
     * @param token
     * @return
     */
    public Claims parseClaims(String token) {
        var key = Keys.hmacShaKeyFor(props.secret().getBytes(StandardCharsets.UTF_8));

        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(props.issuer())
                .build()
                .parseSignedClaims(token) // JWT 파싱 + 검증 수행
                .getPayload(); // Claims(내용) 부분만 반환
    }

    /**
     * Claims에서 "role"을 문자열로 꺼내서 UserRole enum으로 변환
     * @param claims
     * @return
     */
    public UserRole extractRole(Claims claims) {
        String role = claims.get("role", String.class);
        return UserRole.valueOf(role);
    }

    public Date getIssuedAt(String token) {
        Claims claims = parseClaims(token);
        return claims.getIssuedAt();
    }

}
