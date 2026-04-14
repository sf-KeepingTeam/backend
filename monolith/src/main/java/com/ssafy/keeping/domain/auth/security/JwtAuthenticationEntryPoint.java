package com.ssafy.keeping.domain.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.exception.dto.ExceptionDto;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper om;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException authException) throws IOException, ServletException {

        log.warn("인증되지 않은 접근 시도: {} {}", request.getMethod(), request.getRequestURI());
        log.warn("인증 예외: {}", authException.getMessage());

        // 에러 응답 설정
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // 에러 응답 생성
        ExceptionDto exceptionDto = ExceptionDto.builder()
                .error(String.valueOf(ErrorCode.UNAUTHORIZED.getHttpStatus().value()))
                .message(ErrorCode.UNAUTHORIZED.getMessage())
                .timestamp(LocalDateTime.now())
                .path(request.getRequestURI())
                .build();

        // json
        String jsonResponse = om.writeValueAsString(exceptionDto);
        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }
}
