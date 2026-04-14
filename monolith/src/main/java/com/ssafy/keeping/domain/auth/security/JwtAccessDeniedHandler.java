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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper om;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException) throws IOException, ServletException {
        log.warn("권한 없는 접근 시도: {} {} by user: {}",
                request.getMethod(),
                request.getRequestURI(),
                request.getUserPrincipal() != null ? request.getUserPrincipal().getName() : "anonymous");

        log.warn("접근 거부 예외 : {}", accessDeniedException.getMessage());

        // 에러 응답 설정
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        // 에러 응답 생성
        ExceptionDto exceptionDto = ExceptionDto.builder()
                .error(String.valueOf(ErrorCode.FORBIDDEN.getHttpStatus().value()))
                .message(ErrorCode.FORBIDDEN.getMessage())
                .timestamp(LocalDateTime.now())
                .path(request.getRequestURI())
                .build();

        // json
        String jsonResponse = om.writeValueAsString(exceptionDto);
        response.getWriter().write(jsonResponse);
        response.getWriter().flush();
    }
}
