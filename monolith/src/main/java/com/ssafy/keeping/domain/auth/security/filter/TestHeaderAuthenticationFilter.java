package com.ssafy.keeping.domain.auth.security.filter;

import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
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

public class TestHeaderAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER_CUSTOMER_ID = "X-TEST-CUSTOMER-ID";
    public static final String HEADER_ROLE = "X-TEST-ROLE"; // optional

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // 성능 테스트 대상 엔드포인트에만 적용
        String path = request.getRequestURI();
        return !(path.contains("/prepayment/")); // 필요하면 더 구체화
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        // 이미 인증이 있으면 건드리지 않음(JWT 인증이 있으면 그대로 사용)
        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String idHeader = request.getHeader(HEADER_CUSTOMER_ID);
        if (idHeader == null || idHeader.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }

        Long id;
        try {
            id = Long.parseLong(idHeader.trim());
        } catch (NumberFormatException e) {
            filterChain.doFilter(request, response);
            return;
        }

        // role은 기본 CUSTOMER, 필요하면 헤더로 바꿀 수 있게
        String roleHeader = request.getHeader(HEADER_ROLE);
        UserRole role = UserRole.CUSTOMER;
        if (roleHeader != null && !roleHeader.isBlank()) {
            try {
                role = UserRole.valueOf(roleHeader.trim().toUpperCase());
            } catch (Exception ignored) {
                role = UserRole.CUSTOMER;
            }
        }

        UserPrincipal principal = new UserPrincipal(id, role);

        var auth = new UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name()))
        );

        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

}
