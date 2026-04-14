package com.ssafy.keeping.domain.auth.security.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import com.ssafy.keeping.domain.auth.token.AccessTokenService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter { // OncePerRequestFilter를 상속해서 요청 1번당 1번 실행되는 필터

    private final AccessTokenService accessTokenService;
    private final ObjectMapper objectMapper;

    /**
     * 매 요청마다 실행되는 메서드
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException
    {
        // Authorization 헤더가 없거나 "Bearer "로 시작하지 않으면 JWT 인증을 시도하지 않고 다음 필터로 넘긴다.
        // 그 요청을 막을지 말지는 SecurityConfig의 인가 규칙(anyRequest().authenticated()) 또는 뒤 필터/컨트롤러에서 결정됨.
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = header.substring("Bearer ".length()).trim();
        if (token.isBlank() || token.equals("null") || token.equals("undefined")) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            // 토큰 검증/파싱
            Claims claims = accessTokenService.parseClaims(token);
            Long id = Long.valueOf(claims.getSubject()); // 유저 id
            UserRole role = accessTokenService.extractRole(claims);  // CUSTOMER / OWNER

            // UserPrincipal 객체 생성
            UserPrincipal principal = new UserPrincipal(id, role);

            // Authentication 생성
            var auth = new UsernamePasswordAuthenticationToken(
                    principal, // UserPrincipal 객체
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
            );

            // SecurityContext에 심기 (핵심)
            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            // 만료: 프론트가 refresh 트리거하도록 코드 내려줌
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "ACCESS_TOKEN_EXPIRED", "엑세스 토큰이 만료되었습니다.");

        } catch (JwtException e) {
            // 변조/서명/형식 오류 등
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "ACCESS_TOKEN_INVALID", "유효하지 않은 엑세스 토큰입니다.");

        } catch (Exception e) {
            // 예상 못한 에러
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "UNAUTHORIZED", "인증 처리 중 오류가 발생했습니다.");
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message) {
        try {
            response.setStatus(401);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("status", "ERRPR", "code", code, "message", message)
            ));
        } catch (Exception ignored) {}
    }
}

/*

JwtAuthenticationFilter 클래스를
SecurityConfig에 필터로 등록 해주어야 한다.
```
var jwtFilter = new JwtAuthenticationFilter(jwtService, objectMapper);
http.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
```

 */