package com.ssafy.keeping.domain.auth.cookie;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.auth.refresh-cookie")
public class RefreshCookieProperties {
    private String name = "REFRESH_TOKEN";
    private String sameSite = "Lax";
    private boolean secure = true;
    private String domain = "";   // empty면 미설정
    private String path = "/auth";
}
