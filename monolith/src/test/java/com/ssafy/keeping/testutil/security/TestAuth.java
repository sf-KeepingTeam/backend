package com.ssafy.keeping.testutil.security;

import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;

public final class TestAuth {

    private TestAuth() {}

    public static RequestPostProcessor customer(Long customerId) {
        UserPrincipal principal = new UserPrincipal(customerId, UserRole.CUSTOMER);
        var token = new UsernamePasswordAuthenticationToken(
                principal,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))
        );
        return authentication(token);
    }

    public static RequestPostProcessor owner(Long ownerId) {
        UserPrincipal principal = new UserPrincipal(ownerId, UserRole.OWNER);
        var token = new UsernamePasswordAuthenticationToken(
                principal,
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_OWNER"))
        );
        return authentication(token);
    }

    /**
     * 인증이 필요 없는 엔드포인트 테스트용(명시적으로 "anonymous" 느낌)
     * 필요 없으면 삭제해도 됨.
     */
    public static RequestPostProcessor anonymous() {
        var token = new UsernamePasswordAuthenticationToken(
                "anonymous",
                "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))
        );
        return authentication(token);
    }
}
