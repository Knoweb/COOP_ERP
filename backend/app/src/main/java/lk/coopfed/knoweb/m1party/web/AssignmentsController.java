package lk.coopfed.knoweb.m1party.web;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.m1party.api.AssignRole;
import lk.coopfed.knoweb.m1party.api.RevokeRole;
import lk.coopfed.knoweb.m1party.query.SecurityQueries;
import lk.coopfed.knoweb.m1party.web.generated.AssignRoleRequest;
import lk.coopfed.knoweb.m1party.web.generated.AssignmentList;
import lk.coopfed.knoweb.m1party.web.generated.AssignmentResponse;
import lk.coopfed.knoweb.m1party.web.generated.AssignmentsApi;
import lk.coopfed.knoweb.m1party.web.generated.RevokeRoleRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** Role assignments (21A section 5; M1-08): assign, revoke, list. */
@RestController
class AssignmentsController implements AssignmentsApi {

    private final Handles<AssignRole, UUID> assignRole;
    private final Handles<RevokeRole, UUID> revokeRole;
    private final SecurityQueries queries;
    private final CurrentScope currentScope;

    AssignmentsController(
            Handles<AssignRole, UUID> assignRole,
            Handles<RevokeRole, UUID> revokeRole,
            SecurityQueries queries,
            CurrentScope currentScope) {
        this.assignRole = assignRole;
        this.revokeRole = revokeRole;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<Void> assignRole(String idempotencyKey, AssignRoleRequest request) {
        assignRole.handle(
                new AssignRole(request.getUserId(), request.getRoleId(), request.getScopeLocationId()),
                currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> revokeRole(String idempotencyKey, RevokeRoleRequest request) {
        revokeRole.handle(
                new RevokeRole(
                        request.getUserId(), request.getRoleId(), request.getScopeLocationId(), request.getReason()),
                currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<AssignmentList> listAssignments(UUID userId) {
        List<AssignmentResponse> items = queries.listAssignments(userId, currentScope.get()).stream()
                .map(a -> new AssignmentResponse(a.userId(), a.roleId(), a.scopeEntityId())
                        .scopeLocationId(a.scopeLocationId()))
                .toList();
        return ResponseEntity.ok(new AssignmentList(items));
    }
}
