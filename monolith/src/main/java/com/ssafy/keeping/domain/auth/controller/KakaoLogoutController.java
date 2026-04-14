package com.ssafy.keeping.domain.auth.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;

@Controller
@RequiredArgsConstructor
public class KakaoLogoutController {

    private final ClientRegistrationRepository clientRegistrationRepository;

    @Value("${app.kakao.logout-redirect-uri}")
    private String logoutRedirectUri;

    @GetMapping("/auth/logout/kakao")
    public void logoutKakao(HttpServletResponse response) throws IOException {
        ClientRegistration reg = clientRegistrationRepository.findByRegistrationId("kakao-customer");

        String url = UriComponentsBuilder
                .fromHttpUrl("https://kauth.kakao.com/oauth/logout")
                .queryParam("client_id", reg.getClientId())
                .queryParam("logout_redirect_uri", logoutRedirectUri)
                .build(true)
                .toUriString();

        response.sendRedirect(url);
    }
}
