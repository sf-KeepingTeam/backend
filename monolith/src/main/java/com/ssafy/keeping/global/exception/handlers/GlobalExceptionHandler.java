package com.ssafy.keeping.global.exception.handlers;

import com.ssafy.keeping.domain.internal.exception.InternalApiAuthException;
import com.ssafy.keeping.global.exception.CustomException;
import com.ssafy.keeping.global.exception.constants.ErrorCode;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 0. JSON 파싱 실패 (@RequestBody 변환 단계에서 터짐)
     * - 잘못된 JSON / 필드 타입 불일치 / enum 매핑 실패 등
     * - 기존에는 Exception fallback에 걸려 500으로 나갔음
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpServletRequest request
    ) {
        String rootMsg = getRootCauseMessage(ex);
        log.warn("Invalid JSON at {}: {}", request.getRequestURI(), rootMsg, ex);

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("요청 본문(JSON)이 올바르지 않습니다.", HttpStatus.BAD_REQUEST.value()));
    }

    /**
     * 0-1. Content-Type이 JSON이 아닐 때 등 (415)
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleHttpMediaTypeNotSupported(
            HttpMediaTypeNotSupportedException ex,
            HttpServletRequest request
    ) {
        log.warn("Unsupported media type at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error("지원하지 않는 Content-Type 입니다. application/json으로 요청하세요.",
                        HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()));
    }

    /**
     * 0-2. 지원하지 않는 HTTP Method (405)
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex,
            HttpServletRequest request
    ) {
        log.warn("Method not supported at {}: {}", request.getRequestURI(), ex.getMessage());

        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResponse.error("지원하지 않는 HTTP Method 입니다.", HttpStatus.METHOD_NOT_ALLOWED.value()));
    }

    /**
     * 0-3. @RequestParam/@PathVariable 타입 변환 실패 (예: Long 자리에 'abc' 들어옴)
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex,
            HttpServletRequest request
    ) {
        String msg = ex.getName() + ": 올바른 형식이 아닙니다.";
        log.warn("Type mismatch at {}: {}", request.getRequestURI(), msg, ex);

        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(msg, HttpStatus.BAD_REQUEST.value()));
    }

    // 1. @RequestBody 검증 실패 (@Valid)
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodArgumentNotValidException(
            MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String errorMessage = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("입력값이 유효하지 않습니다.");

        log.warn("Validation error at {}: {}", request.getRequestURI(), errorMessage);

        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(errorMessage, HttpStatus.BAD_REQUEST.value()));
    }

    // 2. @RequestParam, @PathVariable 검증 실패
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolationException(
            ConstraintViolationException ex,
            HttpServletRequest request) {

        String errorMessage = ex.getConstraintViolations()
                .stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .findFirst()
                .orElse("잘못된 요청입니다.");

        log.warn("Constraint violation at {}: {}", request.getRequestURI(), errorMessage);

        return ResponseEntity
                .badRequest()
                .body(ApiResponse.error(errorMessage, HttpStatus.BAD_REQUEST.value()));
    }

    // 3. DB 무결성 / 유니크 위반
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<?>> handleDataIntegrity(
            DataIntegrityViolationException e,
            HttpServletRequest request
    ) {
        log.error("DataIntegrityViolationException at {}: {}", request.getRequestURI(), e.getMessage(), e);

        if (isUniqueViolation(e)) {
            // 정확한 에러 메시지 로깅
            Throwable cause = e.getCause();
            while (cause != null) {
                log.error("Cause: {}", cause.getMessage());
                cause = cause.getCause();
            }

            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(ApiResponse.error("중복되는 입력값입니다.", HttpStatus.CONFLICT.value()));
        }

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("무결성 제약 위반", HttpStatus.BAD_REQUEST.value()));
    }

    private boolean isUniqueViolation(Throwable t) {
        while (t != null) {
            if (t instanceof org.hibernate.exception.ConstraintViolationException cve) {
                log.error("ConstraintViolationException - SQL: {}, SQLState: {}, ErrorCode: {}",
                        cve.getSQL(), cve.getSQLState(), cve.getErrorCode());

                String state = cve.getSQLState();
                int code = cve.getErrorCode();
                if ("23000".equals(state) || "23505".equals(state) || code == 1062) return true;
            }
            if (t instanceof java.sql.SQLIntegrityConstraintViolationException) {
                log.error("SQLIntegrityConstraintViolationException: {}", t.getMessage());
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    // 3-1. Internal API 인증 실패
    @ExceptionHandler(InternalApiAuthException.class)
    public ResponseEntity<ApiResponse<Void>> handleInternalApiAuthException(
            InternalApiAuthException e, HttpServletRequest request) {
        log.warn("Internal API 인증 실패: {} at {}", e.getMessage(), request.getRequestURI());

        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(e.getMessage(), HttpStatus.UNAUTHORIZED.value()));
    }

    // 4. 커스텀 예외
    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiResponse<Void>> handleCustomException(CustomException e, HttpServletRequest request) {
        ErrorCode errorCode = e.getErrorCode();

        if (e.getCause() != null) {
            log.warn("Custom exception at {}: {} ({})",
                    request.getRequestURI(),
                    errorCode.name(),
                    errorCode.getMessage(),
                    e);
        } else {
            log.warn("Custom exception at {}: {} ({})",
                    request.getRequestURI(),
                    errorCode.name(),
                    errorCode.getMessage());
        }

        return ResponseEntity
                .status(errorCode.getHttpStatus())
                .body(ApiResponse.error(errorCode.getMessage(), errorCode.getHttpStatus().value()));
    }

    // 5. 알 수 없는 예외 (최종 fallback)
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleException(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error at {}: {}", request.getRequestURI(), ex.getMessage(), ex);

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("서버 내부 오류가 발생했습니다.", HttpStatus.INTERNAL_SERVER_ERROR.value()));
    }

    private String getRootCauseMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage();
    }
}
