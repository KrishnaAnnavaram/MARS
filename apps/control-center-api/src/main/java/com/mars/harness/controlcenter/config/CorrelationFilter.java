package com.mars.harness.controlcenter.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation ID ({@code X-Correlation-Id}, generated when absent or
 * malformed), puts it and the run ID in the logging context, and returns it in the response so an
 * error shown in the browser can be found in the server log.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Pattern RUN = Pattern.compile("/runs/(RUN-[0-9A-Z]{26})");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String id = incoming != null && SAFE.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        MDC.put("correlation_id", id);
        var run = RUN.matcher(request.getRequestURI());
        if (run.find()) {
            MDC.put("mars.run.id", run.group(1));
        }
        response.setHeader(HEADER, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("mars.run.id");
        }
    }
}
