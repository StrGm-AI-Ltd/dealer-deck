package ai.strgm.dealer;

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
    static final String CLIENT = "dealer.client";
    private final DealerProperties props;

    public AuthFilter(final DealerProperties props) {
        this.props = props;
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
        final var header = request.getHeader("Authorization");
        final var key = header != null && header.startsWith("Bearer ") ? header.substring(7) : "";
        final var client = props.clients().stream().filter(c -> c.key().equals(key)).findFirst();
        if (client.isPresent()) {
            request.setAttribute(CLIENT, client.get());
            chain.doFilter(request, response);
        } else if (key.equals(props.metricsKey()) && request.getRequestURI().startsWith("/metrics")) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"message\":\"Invalid API key\",\"type\":\"authentication_error\"}}");
        }
    }
}
