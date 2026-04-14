package com.ssafy.keeping.domain.auth.security.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class NoStoreAuthResponseFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        // 토큰이 응답으로 내려갈 가능성이 있는 경로만 적용
        return !(path.startsWith("/auth")
                || path.startsWith("/signup")
                || path.startsWith("/login/oauth2/code")
                || path.startsWith("/oauth2/authorization")); // optional
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {

        setNoStoreHeaders(response);

        filterChain.doFilter(request, response);
    }

    private void setNoStoreHeaders(HttpServletResponse response) {
        if (response.containsHeader("Cache-Control")) return;

        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0"); // optional (legacy)
    }
}
