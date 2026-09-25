package lk.coopfed.knoweb.kernel.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.stub.ProblemResponses;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Resolves the request scope once, validates the selected active scope and exposes
 * the principal/scope identifiers through MDC for structured logging.
 *
 * K-02: the scope comes from the verified token (TokenCurrentScope); this filter remains the
 * request boundary that validates the choice of active scope.
 */
@Component
@Profile("web")
// After Spring Security's chain (-100), which verifies the bearer token and fills the security
// context this filter's CurrentScope reads; and so also after Spring's RequestContextFilter,
// which fills the RequestContextHolder. Ordered before either, this filter found no request
// or no token and every call to the API answered 500 or ran as nobody.
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 10)
public class ScopeFilter extends OncePerRequestFilter {

    private final CurrentScope currentScope;
    private final ProblemResponses problems;
    private final ObjectMapper mapper;

    public ScopeFilter(CurrentScope currentScope, ProblemResponses problems, ObjectMapper mapper) {
        this.currentScope = currentScope;
        this.problems = problems;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        ScopeContext scope;
        try {
            scope = currentScope.get();
            validate(scope);
        } catch (ProblemException e) {
            // A filter runs outside the DispatcherServlet, so no @ControllerAdvice sees this:
            // without the answer written here a wrong scope header is a 500, not a problem.
            ProblemDetail problem = problems.toProblem(e, request.getLocale());
            response.setStatus(problem.getStatus());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            mapper.writeValue(response.getWriter(), problem);
            return;
        }

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

        if (scope.activeScope() != null && !scope.scopes().contains(scope.activeScope())) {
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
