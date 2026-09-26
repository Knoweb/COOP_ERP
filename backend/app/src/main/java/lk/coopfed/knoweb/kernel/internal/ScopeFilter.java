package lk.coopfed.knoweb.kernel.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.security.LocationOwners;
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
    private final LocationOwners locations;

    public ScopeFilter(
            CurrentScope currentScope, ProblemResponses problems, ObjectMapper mapper, LocationOwners locations) {
        this.currentScope = currentScope;
        this.problems = problems;
        this.mapper = mapper;
        this.locations = locations;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        ScopeContext scope;
        try {
            scope = currentScope.get();
            validate(scope, locations::ownerOf);
            validatePrincipal(
                    scope,
                    request.getRequestURI().substring(request.getContextPath().length()));
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

    static void validate(ScopeContext scope, Function<UUID, Optional<UUID>> ownerOf) {
        if (scope == null) {
            throw new ProblemException("scope.invalid");
        }

        if (scope.activeScope() == null && scope.scopes().size() > 1) {
            throw new ProblemException("scope.required");
        }

        if (scope.activeScope() != null && !holds(scope, scope.activeScope(), ownerOf)) {
            throw new ProblemException("scope.invalid");
        }
    }

    /**
     * A held scope, or a location of an entity the caller holds entity-wide: doc 19 section 3.1
     * expands an entity grant to every location, so the administrator of a society may act at
     * any of its shops, and only its own: the location must belong to that entity (M1's
     * {@code party.location}), else an entity-wide holder could name another entity's shop, and
     * the OWN write policies, which test the entity and the location apart, would let it write.
     */
    static boolean holds(ScopeContext scope, Scope active, Function<UUID, Optional<UUID>> ownerOf) {
        if (scope.scopes().contains(active)) {
            return true;
        }
        return active.locationId() != null
                && scope.scopes().contains(new Scope(active.entityId(), null))
                && ownerOf.apply(active.locationId())
                        .filter(owner -> owner.equals(active.entityId()))
                        .isPresent();
    }

    /**
     * K-08: which principal may call which path (doc 32 section 9; 19A section 2). The till's sync
     * operations take a device token and nothing else, and a device token opens nothing but them:
     * a till's credential is on a machine in a shop, and it must not reach the back office's API.
     * Two sync operations are not the device's: an administrator issues the enrolment code (a user
     * token), and the enrolment itself is called before the device holds any token.
     */
    static void validatePrincipal(ScopeContext scope, String path) {
        boolean device = scope.policyClass() == PolicyClass.DEVICE;
        if (isDeviceOperation(path)) {
            if (!device) {
                throw new ProblemException("sync.device_token_required");
            }
        } else if (device && !isEnrolment(path)) {
            throw new ProblemException("sync.device_token_not_allowed");
        }
    }

    private static final String SYNC = "/v1/sync/";

    static boolean isDeviceOperation(String path) {
        return path.startsWith(SYNC) && !isEnrolment(path) && !path.endsWith("/enrolment-codes");
    }

    private static boolean isEnrolment(String path) {
        return path.startsWith(SYNC + "devices/") && path.endsWith("/enrol");
    }

    private static void putMdc(ScopeContext scope) {
        put("correlationId", scope.correlationId());
        put("userId", scope.userId());
        put("deviceId", scope.deviceId());
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
        MDC.remove("deviceId");
        MDC.remove("entityId");
        MDC.remove("locationId");
        MDC.remove("scopeClass");
    }
}
