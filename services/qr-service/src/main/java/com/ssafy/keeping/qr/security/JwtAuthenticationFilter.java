package com.ssafy.keeping.qr.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.qr.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtProperties jwtProperties;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

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
            Claims claims = parseClaims(token);
            Long id = Long.valueOf(claims.getSubject());
            UserRole role = extractRole(claims);

            UserPrincipal principal = new UserPrincipal(id, role);

            var auth = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
            );

            SecurityContextHolder.getContext().setAuthentication(auth);
            filterChain.doFilter(request, response);

        } catch (ExpiredJwtException e) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "ACCESS_TOKEN_EXPIRED", "엑세스 토큰이 만료되었습니다.");

        } catch (JwtException e) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "ACCESS_TOKEN_INVALID", "유효하지 않은 엑세스 토큰입니다.");

        } catch (Exception e) {
            SecurityContextHolder.clearContext();
            writeUnauthorized(response, "UNAUTHORIZED", "인증 처리 중 오류가 발생했습니다.");
        }
    }

    private Claims parseClaims(String token) {
        var key = Keys.hmacShaKeyFor(jwtProperties.secret().getBytes(StandardCharsets.UTF_8));

        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(jwtProperties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private UserRole extractRole(Claims claims) {
        String role = claims.get("role", String.class);
        return UserRole.valueOf(role);
    }

    private void writeUnauthorized(HttpServletResponse response, String code, String message) {
        try {
            response.setStatus(401);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    Map.of("status", "ERROR", "code", code, "message", message)
            ));
        } catch (Exception ignored) {}
    }
}
