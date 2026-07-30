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

/**
 * Control de tasa de la API de ingesta (sección 2.7 del TDD de Semana 2).
 *
 * <p><b>Por qué existe:</b> la API de ingesta está expuesta a internet, y cada
 * petición aceptada dispara un evento hacia el motor de scoring (que además,
 * si supera el umbral, puede escribir en el almacén de casos). Sin límite de
 * tasa, un actor malicioso podría saturar la API con transacciones sintéticas
 * y hacer crecer sin control el consumo de RU/crédito de Azure.
 *
 * <p><b>Por qué Bucket4j y no una capa de API management:</b> el proyecto no
 * contempla un API Gateway dedicado (nota de alcance de la sección 2.7) — el
 * límite se implementa en la capa de aplicación, sin infraestructura adicional
 * ni costo extra (Bucket4j es una librería en memoria, no requiere Redis ni
 * ningún servicio administrado).
 *
 * <p><b>Origen:</b> por API key si el cliente envía {@code X-API-Key} (útil una
 * vez que existan credenciales por emisor), y si no por IP (primer salto de
 * {@code X-Forwarded-For}, o la IP remota directa).
 *
 * <p><b>Límites elegidos (configurables, ver application.properties) y su
 * justificación</b> — documentado también en
 * docs/decisions/002-semana2-scoring-mensajeria-y-casos.md:
 * <ul>
 *   <li><b>Ráfaga (burst):</b> 10 peticiones / 1 segundo por origen. Permite que
 *   un cliente legítimo envíe varias transacciones casi simultáneas (por ejemplo,
 *   un lote pequeño reintentado) sin ser bloqueado de inmediato.</li>
 *   <li><b>Sostenido:</b> 60 peticiones / 60 segundos por origen (1 req/s en
 *   promedio). Es generoso para el volumen de un proyecto estudiantil en fase de
 *   pruebas, pero acota a un actor que intente saturar la API de forma
 *   sostenida — cada petición aceptada de más cuesta una escritura en Cosmos DB
 *   más una ejecución del motor de scoring.</li>
 * </ul>
 * Ambos límites actúan juntos (el bucket exige cumplir las dos bandas a la vez).
 *
 * <p>Limitación conocida: el mapa de buckets es en memoria y por instancia — con
 * múltiples réplicas del servicio, cada una aplicaría su propio límite (el límite
 * efectivo se multiplicaría por el número de instancias). Aceptable para el
 * alcance actual (una sola instancia); si el proyecto escalara horizontalmente,
 * habría que migrar a un backend distribuido de Bucket4j (p. ej. respaldado por
 * una caché compartida) — no necesario hoy.
 */
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

    /**
     * Azure App Service (y otros reverse proxies) publican {@code X-Forwarded-For}
     * como {@code <ip-cliente>:<puerto-efímero>}, no solo la IP. El puerto efímero
     * cambia en cada conexión TCP nueva del mismo cliente, así que sin esta
     * normalización cada petición concurrente resolvía a un "origen" distinto y el
     * bucket de rate limiting nunca se compartía entre peticiones del mismo cliente
     * (evidencia: banco de pruebas en {@code infra/postman/}, ver
     * {@code docs/REPORTE-PRUEBAS.md}) — 18 peticiones concurrentes sin
     * {@code X-API-Key} recibieron 201 todas, con {@code X-RateLimit-Remaining=9}
     * en TODAS (bucket nuevo cada vez) en vez de decrementar desde un único bucket
     * compartido.
     */
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
        // IPv6 sin corchetes (2+ ':'): se deja tal cual, no lleva puerto anexado.
        if (firstColon != lastColon) {
            return trimmed;
        }
        // Exactamente un ':' -> es "ip:puerto" (IPv4) o "host:puerto".
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
