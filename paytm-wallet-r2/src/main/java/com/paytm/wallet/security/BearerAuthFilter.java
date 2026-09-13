package com.paytm.wallet.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.paytm.wallet.dto.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerAuthFilter extends OncePerRequestFilter {

    public static final String USER_KEY_ATTRIBUTE = "callerUserKey";

    private final TokenHasher tokenHasher;
    private final ObjectMapper objectMapper;

    public BearerAuthFilter(TokenHasher tokenHasher, ObjectMapper objectMapper) {
        this.tokenHasher = tokenHasher;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/health") || path.equals("/metrics") || path.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            unauthorized(response, "Missing bearer token");
            return;
        }

        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isEmpty()) {
            unauthorized(response, "Bearer token cannot be empty");
            return;
        }

        request.setAttribute(USER_KEY_ATTRIBUTE, tokenHasher.hash(token));
        filterChain.doFilter(request, response);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(
                "UNAUTHORIZED",
                message,
                MDC.get("correlation_id"),
                Instant.now()
        ));
    }
}
