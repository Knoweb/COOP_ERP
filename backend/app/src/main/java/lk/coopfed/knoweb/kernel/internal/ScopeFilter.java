package lk.coopfed.knoweb.kernel.internal;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request scope once, validates the selected active scope and exposes
 * the principal/scope identifiers through MDC for structured logging.
 *
 * K-02 replaces the development CurrentScope source with token-backed resolution;
 * this filter remains the request/session boundary.
 */
@Component
@Profile("web")
@Order(-100) // Must run after RequestContextFilter (-105)
public class ScopeFilter extends OncePerRequestFilter {

    private final CurrentScope currentScope;

    public ScopeFilter(CurrentScope currentScope) {
        this.currentScope = currentScope;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain)
            throws ServletException, IOException {

        ScopeContext scope = currentScope.get();

        validate(scope);

        putMdc(scope);

        try {
            chain.doFilter(request, response);
        } finally {
            clearMdc();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();

        return path.startsWith(request.getContextPath() + "/actuator")
                || path.equals(request.getContextPath() + "/error");
    }

    private static void validate(ScopeContext scope) {
        if (scope == null) {
            throw new ProblemException("scope.invalid");
        }

        if (scope.activeScope() == null && scope.scopes().size() > 1) {
            throw new ProblemException("scope.required");
        }

        if (scope.activeScope() != null
                && !scope.scopes().contains(scope.activeScope())) {
            throw new ProblemException("scope.invalid");
        }
    }

    private static void putMdc(ScopeContext scope) {
        put("correlationId", scope.correlationId());
        put("userId", scope.userId());
        put("entityId", scope.entityId());
        put("locationId", scope.locationId());
        put("scopeClass", scope.policyClass());
    }

    private static void put(String key, Object value) {
        if (value != null) {
            MDC.put(key, value.toString());
        }
    }

    private static void clearMdc() {
        MDC.remove("correlationId");
        MDC.remove("userId");
        MDC.remove("entityId");
        MDC.remove("locationId");
        MDC.remove("scopeClass");
    }
}
