package net.kyver.placy.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class SecretKeyValidationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(SecretKeyValidationFilter.class);

    private static final String API_KEY_HEADER = "X-API-Key";
    private static final String SECRET_KEY_HEADER = "X-Secret-Key";
    private static final String CLIENT_VERSION_HEADER = "X-Client-Version";

    private final ConcurrentHashMap<String, AtomicInteger> ipRequestCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ipLastReset = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_MINUTE = 100;

    @Value("${SECRET_KEY:}")
    private String secretKey;

    @Value("${placy.security.rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled;

    private boolean isApiKeyRequired() {
        return secretKey != null && !secretKey.trim().isEmpty() && !secretKey.equals("your_secret_key_here");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {

        String requestPath = request.getRequestURI();
        String clientIp = getClientIpAddress(request);

        if (isExemptPath(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (rateLimitingEnabled && !checkRateLimit(clientIp)) {
            logger.warn("Rate limit exceeded for IP: {} on path: {}", clientIp, requestPath);
            sendErrorResponse(response, 429,
                    "Rate limit exceeded. Please try again later.");
            return;
        }

        if (!isApiKeyRequired()) {
            setAnonymousAuthentication();
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null) {
            providedKey = request.getHeader(SECRET_KEY_HEADER);
        }

        if (providedKey == null || providedKey.trim().isEmpty()) {
            logger.warn("Missing API key for request: {} from IP: {}", requestPath, clientIp);
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "API key required. Please provide X-API-Key header.");
            return;
        }

        if (!isValidApiKey(providedKey)) {
            logger.warn("Invalid API key provided for request: {} from IP: {}", requestPath, clientIp);
            sendErrorResponse(response, HttpServletResponse.SC_UNAUTHORIZED,
                            "Invalid API key provided.");
            return;
        }

        String clientVersion = request.getHeader(CLIENT_VERSION_HEADER);
        logger.debug("Authenticated request: {} from IP: {} (client: {})",
                    requestPath, clientIp, clientVersion != null ? clientVersion : "unknown");

        setApiKeyAuthentication(providedKey);

        filterChain.doFilter(request, response);
    }

    private boolean isExemptPath(String path) {
        return path.equals("/") ||
               path.equals("/error") ||
               path.equals("/health") ||
               path.equals("/info") ||
               path.equals("/favicon.ico") ||
               path.startsWith("/css/") ||
               path.startsWith("/js/") ||
               path.startsWith("/images/") ||
               path.startsWith("/webjars/") ||
               path.startsWith("/actuator/health") ||
               path.startsWith("/swagger-ui/") ||
               path.startsWith("/v3/api-docs/") ||
               path.equals("/api/v2/supported-types");
    }

    private boolean checkRateLimit(String clientIp) {
        if (!rateLimitingEnabled) {
            return true;
        }

        long currentTime = System.currentTimeMillis();
        long currentMinute = currentTime / 60000;

        Long lastReset = ipLastReset.get(clientIp);
        if (lastReset == null || lastReset < currentMinute) {
            ipRequestCounts.put(clientIp, new AtomicInteger(0));
            ipLastReset.put(clientIp, currentMinute);
        }

        AtomicInteger count = ipRequestCounts.computeIfAbsent(clientIp, k -> new AtomicInteger(0));
        return count.incrementAndGet() <= MAX_REQUESTS_PER_MINUTE;
    }

    private boolean isValidApiKey(String providedKey) {
        return secretKey.equals(providedKey);
    }

    private void setApiKeyAuthentication(String apiKey) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            "api-user", apiKey, List.of(new SimpleGrantedAuthority("ROLE_API_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void setAnonymousAuthentication() {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
            "anonymous", null, List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private void sendErrorResponse(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String jsonResponse = String.format(
            "{\"error\":\"%s\",\"status\":%d,\"timestamp\":%d}",
            message, status, System.currentTimeMillis());

        response.getWriter().write(jsonResponse);
    }

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }

        String xForwarded = request.getHeader("X-Forwarded");
        if (xForwarded != null && !xForwarded.isEmpty()) {
            return xForwarded;
        }

        String forwarded = request.getHeader("Forwarded");
        if (forwarded != null && !forwarded.isEmpty()) {
            String[] parts = forwarded.split(";");
            for (String part : parts) {
                if (part.trim().startsWith("for=")) {
                    return part.substring(4).replaceAll("\"", "").trim();
                }
            }
        }

        return request.getRemoteAddr();
    }

    public void clearRateLimitData() {
        ipRequestCounts.clear();
        ipLastReset.clear();
        logger.info("Rate limiting data cleared");
    }

    public java.util.Map<String, Object> getRateLimitStats() {
        return java.util.Map.of(
            "trackedIPs", ipRequestCounts.size(),
            "rateLimitingEnabled", rateLimitingEnabled,
            "maxRequestsPerMinute", MAX_REQUESTS_PER_MINUTE
        );
    }
}
