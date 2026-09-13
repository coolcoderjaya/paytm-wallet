package com.paytm.wallet.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class CallerIdentity {
    public String userKey(HttpServletRequest request) {
        Object value = request.getAttribute(BearerAuthFilter.USER_KEY_ATTRIBUTE);
        if (value == null) {
            throw new IllegalStateException("Caller identity missing after authentication filter");
        }
        return value.toString();
    }
}
