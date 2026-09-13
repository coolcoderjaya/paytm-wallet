package com.paytm.wallet.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String correlationId = resolveCorrelationId(request.getHeader(HEADER));
        long started = System.nanoTime();
        MDC.put("correlation_id", correlationId);
        response.setHeader(HEADER, correlationId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - started) / 1_000_000;
            log.atInfo()
                    .addKeyValue("event", "request_completed")
                    .addKeyValue("method", request.getMethod())
                    .addKeyValue("path", request.getRequestURI())
                    .addKeyValue("status", response.getStatus())
                    .addKeyValue("duration_ms", durationMs)
                    .log("request completed");
            MDC.clear();
        }
    }

    private String resolveCorrelationId(String supplied) {
        if (supplied != null && SAFE_ID.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }
}
