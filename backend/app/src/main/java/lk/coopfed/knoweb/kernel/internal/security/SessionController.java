package lk.coopfed.knoweb.kernel.internal.security;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.internal.job.SystemScope;
import lk.coopfed.knoweb.kernel.session.web.generated.ScopeRef;
import lk.coopfed.knoweb.kernel.session.web.generated.SessionApi;
import lk.coopfed.knoweb.kernel.session.web.generated.SessionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/session} (openapi/session.yaml; CR-19A-9): the caller's own facts as the
 * kernel resolved them for this request, and the permissions resolved for the active scope.
 * The web shell decides what to show from the permission list (doc 30 section 3, "visibility
 * from the resolved permission set"); the server checks every request regardless. The read of
 * the resolver runs in a transaction that carries the caller's scope, as the read interceptor
 * does, because the OWN resolution reads M1's assignments under the caller's own policies.
 */
@RestController
class SessionController implements SessionApi {

    private final CurrentScope currentScope;
    private final PermissionResolver permissions;
    private final SystemScope transactions;

    SessionController(CurrentScope currentScope, PermissionResolver permissions, SystemScope transactions) {
        this.currentScope = currentScope;
        this.permissions = permissions;
        this.transactions = transactions;
    }

    @Override
    public ResponseEntity<SessionResponse> getSession() {
        ScopeContext scope = currentScope.get();

        Set<String> resolved =
                scope.hasActiveScope() ? transactions.inScope(scope, () -> permissions.resolve(scope)) : Set.of();

        SessionResponse response = new SessionResponse(
                scope.userId(),
                policyClass(scope),
                scope.scopes().stream().map(SessionController::ref).toList(),
                List.copyOf(new TreeSet<>(scope.grantedEntities())),
                List.copyOf(new TreeSet<>(resolved)));
        response.setHomeEntityId(scope.homeEntityId());
        response.setActiveScope(scope.activeScope() == null ? null : ref(scope.activeScope()));
        return ResponseEntity.ok(response);
    }

    /** The slice names the classes a user token carries; a principal of another kind (a device) never reaches here. */
    private static SessionResponse.PolicyClassEnum policyClass(ScopeContext scope) {
        try {
            return SessionResponse.PolicyClassEnum.fromValue(scope.policyClass().name());
        } catch (IllegalArgumentException notAUserClass) {
            return SessionResponse.PolicyClassEnum.NONE;
        }
    }

    private static ScopeRef ref(Scope scope) {
        ScopeRef ref = new ScopeRef(scope.entityId());
        ref.setLocationId(scope.locationId());
        return ref;
    }
}
