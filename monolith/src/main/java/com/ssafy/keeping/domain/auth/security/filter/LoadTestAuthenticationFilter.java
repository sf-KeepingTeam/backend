package com.ssafy.keeping.domain.auth.security.filter;

import com.ssafy.keeping.domain.auth.enums.UserRole;
import com.ssafy.keeping.domain.auth.security.principal.UserPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

@Slf4j
public class LoadTestAuthenticationFilter extends OncePerRequestFilter {

  private static final String TEST_USER_ID_HEADER = "X-Test-User-Id";
  private static final String TEST_ROLE_HEADER = "X-Test-Role";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String userIdHeader = request.getHeader(TEST_USER_ID_HEADER);
    String roleHeader = request.getHeader(TEST_ROLE_HEADER);

    // 테스트 헤더가 없으면 다음 필터로 통과
    if (userIdHeader == null || roleHeader == null) {
      filterChain.doFilter(request, response);
      return;
    }

    try {
      Long userId = Long.parseLong(userIdHeader);
      UserRole role = UserRole.valueOf(roleHeader.toUpperCase());

      // 컨트롤러가 @AuthenticationPrincipal UserPrincipal 을 기대하므로,
      // 실제 JwtAuthenticationFilter와 동일하게 UserPrincipal을 principal로 설정한다.
      UserPrincipal principal = new UserPrincipal(userId, role);
      var auth =
          new UsernamePasswordAuthenticationToken(
              principal, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));

      // SecurityContext에 설정
      SecurityContextHolder.getContext().setAuthentication(auth);

      log.debug("LoadTest auth set: userId={}, role={}", userId, role);

    } catch (NumberFormatException e) {
      log.warn("Invalid X-Test-User-Id header value: {}", userIdHeader);
    } catch (IllegalArgumentException e) {
      log.warn("Invalid X-Test-Role header value: {}", roleHeader);
    }

    filterChain.doFilter(request, response);
  }
}
