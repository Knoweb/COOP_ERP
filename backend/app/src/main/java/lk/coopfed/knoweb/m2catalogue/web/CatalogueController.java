package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DeactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.ReactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.UpdateSku;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuCommandRouter;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuFilter;
import lk.coopfed.knoweb.m2catalogue.query.SkuPage;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m2catalogue.web.generated.ActivateSkuRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.CatalogueApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.ReasonRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.SkuDetailsRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.SkuPageResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.SkuResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
class CatalogueController implements CatalogueApi {

    private static final String VIEW = "cat.sku.view";

    private final SkuCommandRouter commands;
    private final CatalogueQueries queries;
    private final CurrentScope currentScope;
    private final PermissionResolver permissions;
    private final boolean enforcePermissions;

    CatalogueController(
            SkuCommandRouter commands,
            CatalogueQueries queries,
            CurrentScope currentScope,
            PermissionResolver permissions,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions) {
        this.commands = commands;
        this.queries = queries;
        this.currentScope = currentScope;
        this.permissions = permissions;
        this.enforcePermissions = enforcePermissions;
    }

    @Override
    public ResponseEntity<SkuResponse> createSku(String idempotencyKey, SkuDetailsRequest request) {

        ScopeContext scope = currentScope.get();

        UUID skuId = commands.create(new CreateSku(details(request)), scope);

        // The owner always sees its own draft; if it does not, the answer is the same problem a
        // read gives, not an untranslated server error.
        SkuView created = queries.getSku(skuId, scope)
                .orElseThrow(() -> new ProblemException("m2.sku.not_found", Map.of("skuId", skuId)));

        return ResponseEntity.created(URI.create("/v1/catalogue/skus/" + skuId)).body(toResponse(created));
    }

    @Override
    public ResponseEntity<SkuPageResponse> listSkus(
            String q, String lang, String status, Integer offset, Integer limit) {

        ScopeContext scope = currentScope.get();
        requireView(scope);

        SkuFilter filter = new SkuFilter(status, q, lang, offset, limit);

        SkuPage page = q == null || q.isBlank() ? queries.listSkus(filter, scope) : queries.searchSku(filter, scope);

        SkuPageResponse response = new SkuPageResponse(
                page.items().stream().map(CatalogueController::toResponse).toList());

        response.setNextOffset(page.nextOffset());

        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<SkuResponse> getSku(UUID skuId) {
        ScopeContext scope = currentScope.get();
        requireView(scope);

        return queries.getSku(skuId, scope)
                .map(CatalogueController::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ProblemException("m2.sku.not_found", Map.of("skuId", skuId)));
    }

    /**
     * The reads declare x-permission cat.sku.view, and no command interceptor runs for a read, so
     * the controller checks it: for the OWN class (the read-only classes resolve no permission;
     * row-level security is what limits them), and only when enforcement is on, as for commands
     * (coop-erp.security.enforce-permissions).
     */
    private void requireView(ScopeContext scope) {
        if (!enforcePermissions || scope == null || scope.policyClass() != PolicyClass.OWN) {
            return;
        }
        if (!permissions.allows(scope, VIEW)) {
            throw new ProblemException("permission.denied", Map.of("permission", VIEW));
        }
    }

    @Override
    public ResponseEntity<Void> updateSku(String idempotencyKey, UUID skuId, SkuDetailsRequest request) {

        commands.update(new UpdateSku(skuId, details(request)), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> activateSku(UUID skuId, String idempotencyKey, ActivateSkuRequest request) {

        ScopeContext scope = currentScope.get();

        if (request.getTarget() == ActivateSkuRequest.TargetEnum.SHARED) {
            commands.activateShared(skuId, scope);
        } else {
            commands.activateLocal(skuId, scope);
        }

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deactivateSku(UUID skuId, String idempotencyKey, ReasonRequest request) {

        commands.deactivate(
                new DeactivateSku(skuId, request.getReasonCode(), request.getReasonText()), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> reactivateSku(UUID skuId, String idempotencyKey, ReasonRequest request) {

        commands.reactivate(
                new ReactivateSku(skuId, request.getReasonCode(), request.getReasonText()), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    private static SkuDetails details(SkuDetailsRequest request) {
        Integer warningDays = request.getExpiryWarningDays();

        return new SkuDetails(
                request.getNameEn(),
                request.getNameSi(),
                request.getNameTa(),
                request.getDescriptionEn(),
                request.getDescriptionSi(),
                request.getDescriptionTa(),
                request.getBaseUomCode(),
                Boolean.TRUE.equals(request.getSoldByWeight()),
                Boolean.TRUE.equals(request.getBatchTracked()),
                Boolean.TRUE.equals(request.getExpiryTracked()),
                Boolean.TRUE.equals(request.getHasPrintedMrp()),
                warningDays == null ? null : warningDays.shortValue(),
                request.getTaxCategoryId(),
                request.getMultiMrpPolicy().getValue(),
                request.getOriginKind().getValue(),
                request.getAttributes());
    }

    private static SkuResponse toResponse(SkuView view) {
        SkuResponse response = new SkuResponse(
                view.skuId(),
                view.skuCode(),
                view.ownerEntityId(),
                SkuResponse.StatusEnum.fromValue(view.status()),
                view.nameEn(),
                view.baseUomCode(),
                view.soldByWeight(),
                view.batchTracked(),
                view.expiryTracked(),
                view.hasPrintedMrp(),
                view.taxCategoryId(),
                view.multiMrpPolicy(),
                view.originKind());

        response.setNameSi(view.nameSi());
        response.setNameTa(view.nameTa());
        response.setDescriptionEn(view.descriptionEn());
        response.setDescriptionSi(view.descriptionSi());
        response.setDescriptionTa(view.descriptionTa());

        if (view.expiryWarningDays() != null) {
            response.setExpiryWarningDays(view.expiryWarningDays().intValue());
        }

        response.setAttributes(view.attributes());

        return response;
    }
}
