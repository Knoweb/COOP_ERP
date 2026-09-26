package lk.coopfed.knoweb.m2catalogue.web;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.LinkBarcodeToBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBarcode;
import lk.coopfed.knoweb.m2catalogue.api.RetireBarcode;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.LinkBarcodeToBatchHandler;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.RegisterBarcodeHandler;
import lk.coopfed.knoweb.m2catalogue.internal.barcode.RetireBarcodeHandler;
import lk.coopfed.knoweb.m2catalogue.query.BarcodeLookup;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.web.generated.BarcodeApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.LinkBarcodeToBatchRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.LookupBatch;
import lk.coopfed.knoweb.m2catalogue.web.generated.LookupResult;
import lk.coopfed.knoweb.m2catalogue.web.generated.LookupResultFallback;
import lk.coopfed.knoweb.m2catalogue.web.generated.RegisterBarcodeRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.Symbology;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The barcode registry and the till's lookup (22A section 4, BarcodeController). */
@RestController
class BarcodeController implements BarcodeApi {

    private final RegisterBarcodeHandler register;
    private final RetireBarcodeHandler retire;
    private final LinkBarcodeToBatchHandler link;
    private final CatalogueQueries queries;
    private final CurrentScope currentScope;
    private final ViewPermission view;

    BarcodeController(
            RegisterBarcodeHandler register,
            RetireBarcodeHandler retire,
            LinkBarcodeToBatchHandler link,
            CatalogueQueries queries,
            CurrentScope currentScope,
            PermissionResolver permissions,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions) {
        this.register = register;
        this.retire = retire;
        this.link = link;
        this.queries = queries;
        this.currentScope = currentScope;
        this.view = new ViewPermission(permissions, enforcePermissions);
    }

    @Override
    public ResponseEntity<Void> registerBarcode(UUID skuId, String idempotencyKey, RegisterBarcodeRequest request) {

        register.handle(
                new RegisterBarcode(
                        skuId,
                        request.getBarcode(),
                        request.getSymbology().getValue(),
                        request.getUomCode(),
                        request.getBatchId()),
                currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> retireBarcode(
            UUID skuId,
            String barcode,
            Symbology symbology,
            String idempotencyKey,
            String reasonCode,
            String reasonText) {

        retire.handle(
                new RetireBarcode(skuId, barcode, symbology.getValue(), reasonCode, reasonText), currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> linkBarcodeToBatch(
            UUID skuId, String barcode, String idempotencyKey, LinkBarcodeToBatchRequest request) {

        link.handle(
                new LinkBarcodeToBatch(skuId, barcode, request.getSymbology().getValue(), request.getBatchId()),
                currentScope.get());

        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<LookupResult> lookupByBarcode(
            String barcode, String gtin, String lot, LocalDate expiry, Symbology symbology, UUID locationId) {

        ScopeContext scope = currentScope.get();
        view.require(scope);

        BarcodeLookup lookup = new BarcodeLookup(
                barcode, gtin, lot, expiry, symbology == null ? null : symbology.getValue(), locationId);

        return queries.lookupByBarcode(lookup, scope)
                .map(BarcodeController::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ProblemException(
                        "m2.barcode.not_found", Map.of("barcode", barcode == null ? "" : barcode)));
    }

    private static LookupResult toResponse(lk.coopfed.knoweb.m2catalogue.query.LookupResult result) {
        LookupResult response = new LookupResult(
                result.skuId(),
                result.skuCode(),
                result.nameEn(),
                result.nameSi(),
                result.nameTa(),
                new LookupResultFallback(result.fallbackSi(), result.fallbackTa()),
                result.uomCode(),
                result.sellThrough(),
                result.hasPrintedMrp(),
                result.soldByWeight());

        response.setFactorToBase(result.factorToBase());
        response.setThumbKey(result.thumbKey());

        if (result.batch() != null) {
            LookupBatch batch =
                    new LookupBatch(result.batch().batchId(), result.batch().batchNo());
            batch.setExpiryDate(result.batch().expiryDate());
            batch.setPrintedMrp(result.batch().printedMrp());
            response.setBatch(batch);
        }

        return response;
    }
}
