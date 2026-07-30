package com.centinela.api.infrastructure.adapter.inbound.web.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final String INGESTION_PATH = "/api/v1/transactions";

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final long burstCapacity;
    private final Duration burstWindow;
    private final long sustainedCapacity;
    private final Duration sustainedWindow;

    public RateLimitFilter(ObjectMapper objectMapper,
                            @Value("${centinela.ratelimit.burst-capacity:10}") long burstCapacity,
                            @Value("${centinela.ratelimit.burst-window-seconds:1}") long burstWindowSeconds,
                            @Value("${centinela.ratelimit.sustained-capacity:60}") long sustainedCapacity,
                            @Value("${centinela.ratelimit.sustained-window-seconds:60}") long sustainedWindowSeconds) {
        this.objectMapper = objectMapper;
        this.burstCapacity = burstCapacity;
        this.burstWindow = Duration.ofSeconds(burstWindowSeconds);
        this.sustainedCapacity = sustainedCapacity;
        this.sustainedWindow = Duration.ofSeconds(sustainedWindowSeconds);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!appliesTo(request)) {
            chain.doFilter(request, response);
            return;
        }

        String origin = resolveOrigin(request);
        Bucket bucket = buckets.computeIfAbsent(origin, key -> newBucket());
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        long waitSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
        log.warn("Rate limit excedido para origen '{}' en {} {}", origin, request.getMethod(), request.getRequestURI());
        writeTooManyRequests(response, origin, waitSeconds);
    }

    private boolean appliesTo(HttpServletRequest request) {
        return "POST".equalsIgnoreCase(request.getMethod()) && INGESTION_PATH.equals(request.getRequestURI());
    }

    private Bucket newBucket() {
        Bandwidth burst = Bandwidth.classic(burstCapacity, Refill.greedy(burstCapacity, burstWindow));
        Bandwidth sustained = Bandwidth.classic(sustainedCapacity, Refill.greedy(sustainedCapacity, sustainedWindow));
        return Bucket.builder()
                .addLimit(burst)
                .addLimit(sustained)
                .build();
    }

    private String resolveOrigin(HttpServletRequest request) {
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "key:" + apiKey;
        }
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return "ip:" + stripPort(forwardedFor.split(",")[0].trim());
        }
        return "ip:" + stripPort(request.getRemoteAddr());
    }

    private static String stripPort(String hostOrIp) {
        if (hostOrIp == null || hostOrIp.isBlank()) {
            return hostOrIp;
        }
        String trimmed = hostOrIp.trim();
        if (trimmed.startsWith("[")) {
            int closingBracket = trimmed.indexOf(']');
            return closingBracket > 0 ? trimmed.substring(1, closingBracket) : trimmed;
        }
        int firstColon = trimmed.indexOf(':');
        int lastColon = trimmed.lastIndexOf(':');
        if (firstColon != lastColon) {
            return trimmed;
        }
        return firstColon >= 0 ? trimmed.substring(0, firstColon) : trimmed;
    }

    private void writeTooManyRequests(HttpServletResponse response, String origin, long waitSeconds) throws IOException {
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(waitSeconds));
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 429);
        body.put("error", "Too Many Requests");
        body.put("message", "Límite de tasa excedido para este origen (" + origin + "). Reintente en "
                + waitSeconds + "s.");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
