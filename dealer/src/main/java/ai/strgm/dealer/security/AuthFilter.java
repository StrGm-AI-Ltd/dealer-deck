package ai.strgm.dealer.security;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

import ai.strgm.dealer.config.DealerProperties;
import ai.strgm.util.AuthorizationHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Clients authenticate with their API key (D85). The metrics key may read /metrics only. */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String CLIENT_CONFIG_ATTRIBUTE = "dealer.client";
    private final DealerProperties properties;

    public AuthFilter(final DealerProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(final HttpServletRequest request) {
        final var path = request.getRequestURI();
        return path.startsWith("/actuator") || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")
                || path.startsWith("/openapi/") || path.equals("/error") || path.equals("/favicon.ico");
    }

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
            final FilterChain chain) throws ServletException, IOException {
        final var apiKey = AuthorizationHeaders.apiKeyIn(request.getHeader("Authorization"));
        final var clientConfig = properties.clients().stream()
                .filter(candidate -> candidate.key().equals(apiKey))
                .findFirst();
        if (clientConfig.isPresent()) {
            request.setAttribute(CLIENT_CONFIG_ATTRIBUTE, clientConfig.get());
            chain.doFilter(request, response);
        } else if (properties.metricsKey().equals(apiKey) && request.getRequestURI().startsWith("/metrics")) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"message\":\"Invalid API key\",\"type\":\"authentication_error\"}}");
        }
    }
}
