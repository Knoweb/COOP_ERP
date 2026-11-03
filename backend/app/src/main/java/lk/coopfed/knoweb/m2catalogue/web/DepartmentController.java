package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.DefineDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.api.UpdateDepartmentCategory;
import lk.coopfed.knoweb.m2catalogue.internal.department.DefineDepartmentCategoryHandler;
import lk.coopfed.knoweb.m2catalogue.internal.department.UpdateDepartmentCategoryHandler;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.DepartmentCategoryView;
import lk.coopfed.knoweb.m2catalogue.web.generated.DefineDepartmentCategoryRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.DepartmentApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.DepartmentCategoryResponse;
import lk.coopfed.knoweb.m2catalogue.web.generated.UpdateDepartmentCategoryRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for merchandise department categories (PGL-M2-08).
 * Implements generated DepartmentApi.
 */
@RestController
class DepartmentController implements DepartmentApi {

    private final DefineDepartmentCategoryHandler defineHandler;
    private final UpdateDepartmentCategoryHandler updateHandler;
    private final CatalogueQueries queries;
    private final CurrentScope currentScope;

    DepartmentController(
            DefineDepartmentCategoryHandler defineHandler,
            UpdateDepartmentCategoryHandler updateHandler,
            CatalogueQueries queries,
            CurrentScope currentScope) {
        this.defineHandler = defineHandler;
        this.updateHandler = updateHandler;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<DepartmentCategoryResponse>> listDepartmentCategories() {
        ScopeContext scope = currentScope.get();
        List<DepartmentCategoryResponse> categories = queries.departmentCategories(scope).stream()
                .map(DepartmentController::toResponse)
                .toList();
        return ResponseEntity.ok(categories);
    }

    @Override
    public ResponseEntity<DepartmentCategoryResponse> defineDepartmentCategory(
            String idempotencyKey, DefineDepartmentCategoryRequest request) {
        ScopeContext scope = currentScope.get();
        UUID id = defineHandler.handle(
                new DefineDepartmentCategory(
                        request.getCode(),
                        request.getNameEn(),
                        request.getNameSi(),
                        request.getNameTa(),
                        request.getDescription(),
                        request.getDisplayOrder()),
                scope);

        DepartmentCategoryView view = queries.getDepartmentCategory(id, scope)
                .orElseThrow(() -> new ProblemException("m2.department.not_found", Map.of("departmentId", id)));

        return ResponseEntity.created(URI.create("/v1/catalogue/departments/" + id))
                .body(toResponse(view));
    }

    @Override
    public ResponseEntity<DepartmentCategoryResponse> getDepartmentCategory(UUID departmentId) {
        ScopeContext scope = currentScope.get();
        return queries.getDepartmentCategory(departmentId, scope)
                .map(DepartmentController::toResponse)
                .map(ResponseEntity::ok)
                .orElseThrow(
                        () -> new ProblemException("m2.department.not_found", Map.of("departmentId", departmentId)));
    }

    @Override
    public ResponseEntity<DepartmentCategoryResponse> updateDepartmentCategory(
            String idempotencyKey, UUID departmentId, UpdateDepartmentCategoryRequest request) {
        ScopeContext scope = currentScope.get();
        String status = request.getStatus() != null ? request.getStatus().getValue() : null;
        updateHandler.handle(
                new UpdateDepartmentCategory(
                        departmentId,
                        request.getNameEn(),
                        request.getNameSi(),
                        request.getNameTa(),
                        request.getDescription(),
                        request.getDisplayOrder(),
                        status),
                scope);

        DepartmentCategoryView view = queries.getDepartmentCategory(departmentId, scope)
                .orElseThrow(
                        () -> new ProblemException("m2.department.not_found", Map.of("departmentId", departmentId)));

        return ResponseEntity.ok(toResponse(view));
    }

    static DepartmentCategoryResponse toResponse(DepartmentCategoryView view) {
        DepartmentCategoryResponse response = new DepartmentCategoryResponse(
                view.departmentId(),
                view.code(),
                view.nameEn(),
                view.displayOrder(),
                DepartmentCategoryResponse.StatusEnum.fromValue(view.status()));
        response.setNameSi(view.nameSi());
        response.setNameTa(view.nameTa());
        response.setDescription(view.description());
        return response;
    }
}
