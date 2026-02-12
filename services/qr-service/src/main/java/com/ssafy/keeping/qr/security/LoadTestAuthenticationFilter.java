package com.ssafy.keeping.qr.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * 로드테스트 전용 인증 필터.
 * X-Test-User-Id, X-Test-User-Role 헤더로 인증을 우회합니다.
 * loadtest.backdoor.enabled=true 일 때만 활성화됩니다.
 */
public class LoadTestAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        String userId = request.getHeader("X-Test-User-Id");
        String userRole = request.getHeader("X-Test-User-Role");

        if (userId != null && userRole != null) {
            try {
                Long id = Long.parseLong(userId);
                UserRole role = UserRole.valueOf(userRole);
                UserPrincipal principal = new UserPrincipal(id, role);

                var auth = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (IllegalArgumentException e) {
                // 잘못된 헤더 값은 무시하고 다음 필터로 진행
            }
        }

        filterChain.doFilter(request, response);
    }
}
