package com.ssafy.keeping.domain.auth.handler;

import com.ssafy.keeping.domain.auth.cookie.RefreshCookieManager;
import com.ssafy.keeping.domain.auth.enums.AuthProvider;
import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketPayload;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketPayloadFactory;
import com.ssafy.keeping.domain.auth.signup.ticket.SignupTicketService;
import com.ssafy.keeping.domain.auth.token.RefreshTokenService;
import com.ssafy.keeping.domain.user.customer.service.CustomerService;
import com.ssafy.keeping.domain.user.owner.service.OwnerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class OAuth2SuccessHandler implements AuthenticationSuccessHandler {

    private final CustomerService customerService;
    private final OwnerService ownerService;
    private final SignupTicketService signupTicketService; // 미가입일 때 Redis에 티켓 저장하고 ticket(UUID) 발급

    private final RefreshTokenService refreshTokenService; //  refreshToken(UUID) 발급
    private final RefreshCookieManager refreshCookieManager;

    @Value("${fe.base-url}")
    private String redirectBaseUrl;

    @Value("${app.auth.redirect-path}")
    private String redirectPath;

    // OAuth2 인증이 성공했을 때 스프링이 호출해주는 메서드
    // authentication 안에 OAuth2 로그인 결과(Principal, registrationId 등)가 들어있다.
    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        try {
            OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;

            // application.yml에서 등록한 OAuth2 클라이언트 이름
            String registrationId = token.getAuthorizedClientRegistrationId(); // (kakao-customer / kakao-owner)

            // role 결정
            UserRole role = switch (registrationId) {
                case "kakao-customer" -> UserRole.CUSTOMER;
                case "kakao-owner" -> UserRole.OWNER;
                default -> throw new IllegalStateException("Unknown registrationId: " + registrationId);
            };

            OAuth2User user = token.getPrincipal();
            String providerId = user.getName(); // 카카오 id (user-name-attribute=id 설정)

            AuthProvider providerType = AuthProvider.KAKAO; // 지금은 카카오만 붙이니까 고정

            if (role == UserRole.CUSTOMER) {
                // 회원가입된 사용자가 있는지 조회
                var existing = customerService.findByProviderTypeAndProviderId(providerType, providerId);
                if (existing.isPresent()) { // 있으면 → JWT 발급해서 SUCCESS 응답
                    long userId = existing.get().getCustomerId();
                    respondLoginSuccess(response, userId, role);
                    return;
                }
            } else {
                var existing = ownerService.findByProviderTypeAndProviderId(providerType, providerId);
                if (existing.isPresent()) {
                    long userId = existing.get().getOwnerId();
                    respondLoginSuccess(response, userId, role);
                    return;
                }
            }

            // 동의항목에서 받은 프로필 정보 추출
            Map<String, Object> attrs = user.getAttributes();
            String nickname = getKakaoNickname(attrs);
            String profileUrl = getKakaoProfileImageUrl(attrs);

            // 미가입 → ticket 발급 후 role별 회원가입 페이지로 리다이렉트
            SignupTicketPayload payload = SignupTicketPayloadFactory.payload(role, providerType, providerId, nickname, profileUrl);
            String ticket = signupTicketService.create(payload, Duration.ofMinutes(10));

            String registerPath = (role == UserRole.CUSTOMER) ? "/customer/register" : "/owner/register";
            String signupRedirectUrl = UriComponentsBuilder.fromUriString(redirectBaseUrl + registerPath)
                    .queryParam("ticket", ticket)
                    .build().toUriString();
            response.sendRedirect(signupRedirectUrl);
        } catch (Exception e) {
            try {
                String errorRedirectUrl = UriComponentsBuilder.fromUriString(redirectBaseUrl + redirectPath)
                        .queryParam("status", "ERROR")
                        .build().toUriString();
                response.sendRedirect(errorRedirectUrl);
            } catch (Exception ignored) {}
        }
    }

    private void respondLoginSuccess(HttpServletResponse response, long userId, UserRole role) throws Exception {
        // 1) refresh 발급 (opaque UUID, Redis 저장)
        var issued = refreshTokenService.issueSingleSession(userId, role);

        // 2) refresh를 HttpOnly 쿠키로 내려줌
        ResponseCookie cookie = refreshCookieManager.issue(issued.token(), issued.ttlSeconds());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        // 3) 프론트 로그인 성공 페이지로 리다이렉트 (accessToken은 프론트에서 POST /auth/refresh로 발급)
        String redirectUrl = UriComponentsBuilder.fromUriString(redirectBaseUrl + redirectPath)
                .queryParam("status", "SUCCESS")
                .build().toUriString();
        response.sendRedirect(redirectUrl);
    }

    @SuppressWarnings("unchecked")
    private static Map<?, ?> getKakaoProfile(Map<String, Object> attrs) {
        Object kakaoAccountObj = attrs.get("kakao_account");
        if (!(kakaoAccountObj instanceof Map<?, ?> kakaoAccount)) return null;

        Object profileObj = kakaoAccount.get("profile");
        if (!(profileObj instanceof Map<?, ?> profile)) return null;

        return profile;
    }

    private static String getKakaoNickname(Map<String, Object> attrs) {
        Map<?, ?> profile = getKakaoProfile(attrs);
        if (profile == null) return null;
        Object v = profile.get("nickname");
        return v != null ? String.valueOf(v) : null;
    }

    private static String getKakaoProfileImageUrl(Map<String, Object> attrs) {
        Map<?, ?> profile = getKakaoProfile(attrs);
        if (profile == null) return null;
        Object v = profile.get("thumbnail_image_url"); // 고화질을 원하는 경우 profile_image_url 로 변경...
        return v != null ? String.valueOf(v) : null;
    }

}
/*

OAuth2JsonSuccessHandler 이 핸들러가 동작할 수 있도록
SecurityConfig에

```
.oauth2Login(oauth -> oauth.successHandler(successHandler))
```
기본 동작 대신 내 핸들러가 동작할 수 있도록 등록해주어야 한다.

 */