package net.kyver.placy.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import net.kyver.placy.config.EnvironmentSetup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class SecretKeyValidationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(SecretKeyValidationFilter.class);
    private static final String SECRET_KEY_HEADER = "X-Secret-Key";

    @Autowired
    private EnvironmentSetup environmentSetup;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain filterChain) throws ServletException, IOException {

        String requestPath = request.getRequestURI();
        if (isExemptPath(requestPath)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!environmentSetup.isSecretKeyEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String providedKey = request.getHeader(SECRET_KEY_HEADER);

        if (providedKey == null || providedKey.trim().isEmpty()) {
            logger.warn("Missing secret key header for request: {} from IP: {}",
                       requestPath, getClientIpAddress(request));
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid secret key\"}");
            return;
        }

        if (!environmentSetup.getSecretKey().equals(providedKey)) {
            logger.warn("Invalid secret key provided for request: {} from IP: {}",
                       requestPath, getClientIpAddress(request));
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Missing or invalid secret key\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isExemptPath(String path) {
        return path.equals("/") ||
               path.equals("/error") ||
               path.equals("/health") ||
               path.startsWith("/actuator/health");
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

        return request.getRemoteAddr();
    }
}
