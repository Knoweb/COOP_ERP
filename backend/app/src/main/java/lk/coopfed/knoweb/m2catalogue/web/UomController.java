package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.DefineUom;
import lk.coopfed.knoweb.m2catalogue.api.UpdateUom;
import lk.coopfed.knoweb.m2catalogue.internal.unit.DefineUomHandler;
import lk.coopfed.knoweb.m2catalogue.internal.unit.UpdateUomHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.UomView;
import lk.coopfed.knoweb.m2catalogue.web.generated.DefineUomRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.UomApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.UomDetailResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.UpdateUomRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for units of measure (PGL-M2-04).
 * Implements generated UomApi.
 */
@RestController
class UomController implements UomApi {

    private final DefineUomHandler defineHandler;
    private final UpdateUomHandler updateHandler;
    private final CatalogueQueries queries;
    private final CurrentScope currentScope;

    UomController(
            DefineUomHandler defineHandler,
            UpdateUomHandler updateHandler,
            CatalogueQueries queries,
            CurrentScope currentScope) {
        this.defineHandler = defineHandler;
        this.updateHandler = updateHandler;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<UomDetailResponse>> listUoms(Boolean includeInactive) {
        ScopeContext scope = currentScope.get();
        boolean inc = Boolean.TRUE.equals(includeInactive);
        List<UomDetailResponse> uoms = queries.listUoms(inc, scope).stream()
                .map(UomController::toResponse)
                .toList();
        return ResponseEntity.ok(uoms);
    }

    @Override
    public ResponseEntity<UomDetailResponse> defineUom(String idempotencyKey, DefineUomRequest request) {
        ScopeContext scope = currentScope.get();
        String code = defineHandler.handle(
                new DefineUom(
                        request.getUomCode(),
                        request.getNameEn(),
                        request.getNameSi(),
                        request.getNameTa(),
                        request.getDescription(),
                        request.getWeight()),
                scope);

        UomView view = queries.getUom(code, scope)
                .orElseThrow(() -> new ProblemException("m2.uom.not_found", Map.of("uomCode", code)));

        return ResponseEntity.created(URI.create("/v1/catalogue/uoms/" + code)).body(toResponse(view));
    }

    @Override
    public ResponseEntity<UomDetailResponse> getUom(String uomCode) {
        ScopeContext scope = currentScope.get();
        return queries.getUom(uomCode, scope)
                .map(UomController::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new ProblemException("m2.uom.not_found", Map.of("uomCode", uomCode)));
    }

    @Override
    public ResponseEntity<UomDetailResponse> updateUom(
            String idempotencyKey, String uomCode, UpdateUomRequest request) {
        ScopeContext scope = currentScope.get();
        String status = request.getStatus() != null ? request.getStatus().getValue() : null;
        updateHandler.handle(
                new UpdateUom(
                        uomCode,
                        request.getNameEn(),
                        request.getNameSi(),
                        request.getNameTa(),
                        request.getDescription(),
                        request.getWeight(),
                        status),
                scope);

        UomView view = queries.getUom(uomCode, scope)
                .orElseThrow(() -> new ProblemException("m2.uom.not_found", Map.of("uomCode", uomCode)));

        return ResponseEntity.ok(toResponse(view));
    }

    static UomDetailResponse toResponse(UomView view) {
        UomDetailResponse response = new UomDetailResponse(
                view.uomCode(),
                view.nameEn(),
                view.weight(),
                UomDetailResponse.StatusEnum.fromValue(view.status() != null ? view.status() : "ACTIVE"));
        response.setNameSi(view.nameSi());
        response.setNameTa(view.nameTa());
        response.setDescription(view.description());
        response.setCreatedAt(view.createdAt());
        return response;
    }
}
