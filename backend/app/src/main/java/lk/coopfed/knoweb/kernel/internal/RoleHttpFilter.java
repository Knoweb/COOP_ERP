package lk.coopfed.knoweb.kernel.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * An instance without the web role serves health and metrics and nothing else (17A section
 * 4.1: worker and ingest start "HTTP endpoints except health and metrics" not at all).
 *
 * <p>One filter in the kernel, so that no module has to mark its controllers: every module's
 * API disappears from a worker or ingest instance at once. It answers 404, the honest answer:
 * on this instance the API does not exist. It runs first, before the idempotency filter, so
 * such an instance does no work at all for a request it will not serve.
 */
@Component
@Profile("!web")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RoleHttpFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith(request.getContextPath() + "/actuator")) {
            chain.doFilter(request, response);
        } else {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
