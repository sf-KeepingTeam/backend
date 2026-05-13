package com.ssafy.keeping.qr.acl;

import com.ssafy.keeping.qr.common.constants.HttpHeaderConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
public class InternalHeaderProvider {

    @Value("${internal.auth-token}")
    private String internalAuthToken;

    public HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaderConstants.X_INTERNAL_AUTH, internalAuthToken);
        return headers;
    }
}
