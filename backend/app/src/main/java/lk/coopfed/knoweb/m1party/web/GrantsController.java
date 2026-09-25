package lk.coopfed.knoweb.m1party.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.GrantExternalView;
import lk.coopfed.knoweb.m1party.api.RevokeExternalView;
import lk.coopfed.knoweb.m1party.query.ExternalGrantQueries;
import lk.coopfed.knoweb.m1party.query.ExternalGrantView;
import lk.coopfed.knoweb.m1party.web.generated.ExternalGrantList;
import lk.coopfed.knoweb.m1party.web.generated.ExternalGrantResponse;
import lk.coopfed.knoweb.m1party.web.generated.GrantExternalViewRequest;
import lk.coopfed.knoweb.m1party.web.generated.GrantsApi;
import lk.coopfed.knoweb.m1party.web.generated.RevokeExternalViewRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** External grants (21A section 5: /v1/security/external-grants; M1-09). */
@RestController
class GrantsController implements GrantsApi {

    private final Handles<GrantExternalView, UUID> grantExternalView;
    private final Handles<RevokeExternalView, UUID> revokeExternalView;
    private final ExternalGrantQueries queries;
    private final CurrentScope currentScope;

    GrantsController(
            Handles<GrantExternalView, UUID> grantExternalView,
            Handles<RevokeExternalView, UUID> revokeExternalView,
            ExternalGrantQueries queries,
            CurrentScope currentScope) {
        this.grantExternalView = grantExternalView;
        this.revokeExternalView = revokeExternalView;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<ExternalGrantResponse> grantExternalView(
            String idempotencyKey, GrantExternalViewRequest request) {

        ScopeContext scope = currentScope.get();

        UUID grantId = grantExternalView.handle(
                new GrantExternalView(
                        request.getGranteeUserId(),
                        List.copyOf(request.getScopeEntityIds()),
                        request.getValidFrom(),
                        request.getValidUntil(),
                        request.getReason()),
                scope);

        ExternalGrantView created = queries.getExternalGrant(grantId, scope).orElseThrow();

        return ResponseEntity.created(URI.create(GrantsApi.PATH_LIST_EXTERNAL_GRANTS))
                .body(toResponse(created));
    }

    @Override
    public ResponseEntity<ExternalGrantList> listExternalGrants() {

        List<ExternalGrantResponse> items = queries.listExternalGrants(currentScope.get()).stream()
                .map(GrantsController::toResponse)
                .toList();

        return ResponseEntity.ok(new ExternalGrantList(items));
    }

    @Override
    public ResponseEntity<Void> revokeExternalView(
            UUID grantId, String idempotencyKey, RevokeExternalViewRequest request) {

        revokeExternalView.handle(new RevokeExternalView(grantId, request.getReason()), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    private static ExternalGrantResponse toResponse(ExternalGrantView view) {
        return new ExternalGrantResponse(
                view.grantId(),
                view.granteeUserId(),
                view.scopeEntityIds(),
                view.validFrom(),
                view.validUntil(),
                view.reason(),
                ExternalGrantResponse.StatusEnum.fromValue(view.status()));
    }
}
