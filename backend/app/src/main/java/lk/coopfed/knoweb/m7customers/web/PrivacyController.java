package lk.coopfed.knoweb.m7customers.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m7customers.api.FulfilDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.RecordDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.api.RefuseDataSubjectRequest;
import lk.coopfed.knoweb.m7customers.query.PrivacyQueries;
import lk.coopfed.knoweb.m7customers.web.generated.FulfilPrivacyRequest;
import lk.coopfed.knoweb.m7customers.web.generated.PrivacyApi;
import lk.coopfed.knoweb.m7customers.web.generated.PrivacyRequest;
import lk.coopfed.knoweb.m7customers.web.generated.PrivacyRequestRequest;
import lk.coopfed.knoweb.m7customers.web.generated.RefusePrivacyRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The data-subject requests of the society (27A section 5, /v1/privacy/requests). */
@RestController
class PrivacyController implements PrivacyApi {

    private final Handles<RecordDataSubjectRequest, UUID> record;
    private final Handles<FulfilDataSubjectRequest, UUID> fulfil;
    private final Handles<RefuseDataSubjectRequest, UUID> refuse;
    private final PrivacyQueries privacy;
    private final CurrentScope currentScope;

    PrivacyController(
            Handles<RecordDataSubjectRequest, UUID> record,
            Handles<FulfilDataSubjectRequest, UUID> fulfil,
            Handles<RefuseDataSubjectRequest, UUID> refuse,
            PrivacyQueries privacy,
            CurrentScope currentScope) {
        this.record = record;
        this.fulfil = fulfil;
        this.refuse = refuse;
        this.privacy = privacy;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<PrivacyRequest>> listPrivacyRequests(String status) {
        return ResponseEntity.ok(privacy.requests(status, currentScope.get()).stream()
                .map(CustomerResponses::privacyRequest)
                .toList());
    }

    @Override
    public ResponseEntity<PrivacyRequest> recordPrivacyRequest(String idempotencyKey, PrivacyRequestRequest request) {
        ScopeContext scope = currentScope.get();
        UUID requestId = record.handle(
                new RecordDataSubjectRequest(
                        request.getCustomerId(), request.getKind().getValue(), request.getNotes()),
                scope);
        return ResponseEntity.created(URI.create("/v1/privacy/requests/" + requestId))
                .body(view(requestId, scope));
    }

    @Override
    public ResponseEntity<PrivacyRequest> fulfilPrivacyRequest(
            String idempotencyKey, UUID requestId, FulfilPrivacyRequest request) {
        ScopeContext scope = currentScope.get();
        fulfil.handle(new FulfilDataSubjectRequest(requestId, request.getOutcome()), scope);
        return ResponseEntity.ok(view(requestId, scope));
    }

    @Override
    public ResponseEntity<PrivacyRequest> refusePrivacyRequest(
            String idempotencyKey, UUID requestId, RefusePrivacyRequest request) {
        ScopeContext scope = currentScope.get();
        refuse.handle(new RefuseDataSubjectRequest(requestId, request.getGround()), scope);
        return ResponseEntity.ok(view(requestId, scope));
    }

    @Override
    public ResponseEntity<Map<String, Object>> getPrivacyExport(UUID requestId) {
        return privacy.accessExport(requestId, currentScope.get())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private PrivacyRequest view(UUID requestId, ScopeContext scope) {
        return CustomerResponses.privacyRequest(
                privacy.request(requestId, scope).orElseThrow());
    }
}
