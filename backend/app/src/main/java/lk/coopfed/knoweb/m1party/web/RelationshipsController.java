package lk.coopfed.knoweb.m1party.web;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m1party.api.ActivateRelationship;
import lk.coopfed.knoweb.m1party.api.AmendRelationshipTerms;
import lk.coopfed.knoweb.m1party.api.OpenTradingRelationship;
import lk.coopfed.knoweb.m1party.api.SuspendRelationship;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipSide;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m1party.web.generated.AmendTermsRequest;
import lk.coopfed.knoweb.m1party.web.generated.OpenRelationshipRequest;
import lk.coopfed.knoweb.m1party.web.generated.RelationshipReasonRequest;
import lk.coopfed.knoweb.m1party.web.generated.RelationshipResponse;
import lk.coopfed.knoweb.m1party.web.generated.RelationshipsApi;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RelationshipsController implements RelationshipsApi {

    private final Handles<OpenTradingRelationship, UUID> open;
    private final Handles<ActivateRelationship, UUID> activate;
    private final Handles<AmendRelationshipTerms, UUID> amend;
    private final Handles<SuspendRelationship, UUID> suspend;
    private final RelationshipQueries queries;
    private final CurrentScope currentScope;

    RelationshipsController(
            Handles<OpenTradingRelationship, UUID> open,
            Handles<ActivateRelationship, UUID> activate,
            Handles<AmendRelationshipTerms, UUID> amend,
            Handles<SuspendRelationship, UUID> suspend,
            RelationshipQueries queries,
            CurrentScope currentScope) {
        this.open = open;
        this.activate = activate;
        this.amend = amend;
        this.suspend = suspend;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<RelationshipResponse> openTradingRelationship(
            String idempotencyKey, OpenRelationshipRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = open.handle(
                new OpenTradingRelationship(
                        request.getBuyerEntityId(),
                        request.getPriceListId(),
                        request.getCreditLimit(),
                        request.getPaymentTermsDays(),
                        request.getDiscrepancyWindowDays(),
                        request.getOrderLockHoursBeforeEta(),
                        request.getAllocationRule() == null
                                ? null
                                : request.getAllocationRule().getValue(),
                        request.getEffectiveFrom(),
                        request.getEffectiveTo()),
                scope);
        return created(id, scope);
    }

    @Override
    public ResponseEntity<Void> activateRelationship(UUID relationshipId, String idempotencyKey) {
        activate.handle(new ActivateRelationship(relationshipId), currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<RelationshipResponse> amendRelationshipTerms(
            UUID relationshipId, String idempotencyKey, AmendTermsRequest request) {
        ScopeContext scope = currentScope.get();
        UUID next = amend.handle(
                new AmendRelationshipTerms(
                        relationshipId,
                        request.getEffectiveFrom(),
                        request.getPriceListId(),
                        request.getCreditLimit(),
                        request.getPaymentTermsDays(),
                        request.getDiscrepancyWindowDays(),
                        request.getOrderLockHoursBeforeEta(),
                        request.getAllocationRule() == null
                                ? null
                                : request.getAllocationRule().getValue(),
                        request.getReasonCode(),
                        request.getReasonText()),
                scope);
        return created(next, scope);
    }

    @Override
    public ResponseEntity<Void> suspendRelationship(
            UUID relationshipId, String idempotencyKey, RelationshipReasonRequest request) {
        suspend.handle(
                new SuspendRelationship(relationshipId, request.getReasonCode(), request.getReasonText()),
                currentScope.get());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<RelationshipResponse> getRelationship(UUID relationshipId) {
        return queries.getRelationship(relationshipId, currentScope.get())
                .map(RelationshipsController::toResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<List<RelationshipResponse>> listRelationships(String side) {
        RelationshipSide wanted = side == null || side.isBlank()
                ? null
                : RelationshipSide.valueOf(side.strip().toUpperCase(Locale.ROOT));
        List<RelationshipResponse> rows = queries.listRelationships(wanted, currentScope.get()).stream()
                .map(RelationshipsController::toResponse)
                .toList();
        return ResponseEntity.ok(rows);
    }

    private ResponseEntity<RelationshipResponse> created(UUID id, ScopeContext scope) {
        RelationshipView view = queries.getRelationship(id, scope).orElseThrow();
        return ResponseEntity.created(URI.create(RelationshipsApi.PATH_LIST_RELATIONSHIPS + "/" + id))
                .body(toResponse(view));
    }

    private static RelationshipResponse toResponse(RelationshipView view) {
        RelationshipResponse response = new RelationshipResponse(
                view.relationshipId(),
                view.sellerEntityId(),
                view.buyerEntityId(),
                view.discrepancyWindowDays(),
                view.orderLockHoursBeforeEta(),
                RelationshipResponse.AllocationRuleEnum.fromValue(view.allocationRule()),
                RelationshipResponse.StatusEnum.fromValue(view.status()),
                view.effectiveFrom());
        response.setPriceListId(view.priceListId());
        response.setCreditLimit(view.creditLimit());
        response.setPaymentTermsDays(view.paymentTermsDays());
        response.setEffectiveTo(view.effectiveTo());
        return response;
    }
}
