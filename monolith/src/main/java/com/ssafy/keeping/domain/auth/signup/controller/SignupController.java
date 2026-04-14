package com.ssafy.keeping.domain.auth.signup.controller;

import com.ssafy.keeping.domain.auth.cookie.RefreshCookieManager;
import com.ssafy.keeping.domain.auth.signup.dto.CustomerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.dto.OwnerSignupRequest;
import com.ssafy.keeping.domain.auth.signup.dto.SignupResponse;
import com.ssafy.keeping.domain.auth.signup.service.SignupFacade;
import com.ssafy.keeping.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/signup")
@RequiredArgsConstructor
public class SignupController {

    private final SignupFacade signupFacade;
    private final RefreshCookieManager refreshCookieManager;

    @PostMapping("/customer")
    public ResponseEntity<ApiResponse<SignupResponse>> signupCustomer(@Valid @RequestBody CustomerSignupRequest req) {
        var issued = signupFacade.signupCustomer(req);

        ResponseCookie cookie = refreshCookieManager.issue(issued.refreshToken(), issued.refreshTtlSeconds());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success("고객 회원가입 완료", HttpStatus.CREATED.value(), issued.body()));
    }

    @PostMapping("/owner")
    public ResponseEntity<ApiResponse<SignupResponse>> signupOwner(@Valid @RequestBody OwnerSignupRequest req) {
        var issued = signupFacade.signupOwner(req);

        ResponseCookie cookie = refreshCookieManager.issue(issued.refreshToken(), issued.refreshTtlSeconds());

        return ResponseEntity.status(HttpStatus.CREATED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.PRAGMA, "no-cache")
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success("점주 회원가입 완료", HttpStatus.CREATED.value(), issued.body()));
    }

}
