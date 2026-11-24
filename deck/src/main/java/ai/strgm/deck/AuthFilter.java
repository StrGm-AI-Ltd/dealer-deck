package ai.strgm.deck;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Every DECK endpoint requires DEALER's DECK key (D104), except health, Swagger UI and the API documents. */
@Component
public class AuthFilter extends OncePerRequestFilter {
    private final DeckProperties props;

    public AuthFilter(final DeckProperties props) {
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
        if (header == null || !header.equals("Bearer " + props.apiKey())) {
            response.setStatus(401);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":{\"message\":\"Missing or invalid DECK key\",\"type\":\"authentication_error\"}}");
            return;
        }
        chain.doFilter(request, response);
    }
}
