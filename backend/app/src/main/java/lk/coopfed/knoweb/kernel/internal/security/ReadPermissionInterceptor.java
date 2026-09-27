package lk.coopfed.knoweb.kernel.internal.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The kernel enforces the {@code x-permission} of every GET on the generated interfaces
 * (decided 27 September 2026, CR-19A-9; doc 19 section 3.4: "permission code required by the
 * operation", reads included), so no controller checks a read by hand and a module cannot
 * forget one. Before the controller runs: the request's path template (the one the slice
 * declares and the generated interface maps) names the operation, {@link SliceOperations}
 * gives its permission, and {@link PermissionGate#requireRead} decides, under the same
 * enforcement switch as a command.
 *
 * <p>Who is checked: a request that names a user. A device token (a till's sync reads) is the
 * device principal, admitted to its own operations by {@code ScopeFilter} and to nothing else;
 * the kernel's system scopes never come over HTTP.
 *
 * <p>The check runs in a transaction that carries the caller's scope ({@link SystemScope#inScope}):
 * the resolver reads M1's assignments under the caller's own row-level security, which needs
 * the scope on the connection, and a GET has no transaction of its own yet at this point.
 *
 * <p>A GET under {@code /v1} that no slice describes is a programming error, not a request to
 * refuse quietly: the generated interfaces are the only controllers (ArchitectureTests), so
 * the slice that declared the operation is the one this class read.
 */
@Component
@Profile("web")
public class ReadPermissionInterceptor implements HandlerInterceptor {

    private final CurrentScope currentScope;
    private final SliceOperations slices;
    private final PermissionGate gate;
    private final SystemScope transactions;

    public ReadPermissionInterceptor(
            CurrentScope currentScope, SliceOperations slices, PermissionGate gate, SystemScope transactions) {
        this.currentScope = currentScope;
        this.slices = slices;
        this.gate = gate;
        this.transactions = transactions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod) || !"GET".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String permission = slices.permissionOf("GET", pattern == null ? null : pattern.toString());
        if (permission == null) {
            throw new IllegalStateException("GET " + pattern + " is served by " + handler
                    + " but no OpenAPI slice describes it: every operation is declared in openapi/<module>.yaml"
                    + " with its x-permission (AGENTS.md; 17A section 3)");
        }
        if (SliceOperations.AUTHENTICATED.equals(permission)) {
            return true;
        }
        ScopeContext scope = currentScope.get();
        if (scope.userId() == null) {
            return true;
        }
        transactions.inScope(scope, () -> {
            gate.requireRead(scope, permission);
            return null;
        });
        return true;
    }

    /** Registers the interceptor for the API paths; the actuator and the error page carry no slice. */
    @Configuration
    @Profile("web")
    static class Registration implements WebMvcConfigurer {

        private final ReadPermissionInterceptor interceptor;

        Registration(ReadPermissionInterceptor interceptor) {
            this.interceptor = interceptor;
        }

        @Override
        public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(interceptor).addPathPatterns("/v1/**");
        }
    }
}
