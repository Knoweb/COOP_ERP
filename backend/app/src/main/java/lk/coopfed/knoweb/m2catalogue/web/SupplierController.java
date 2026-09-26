package lk.coopfed.knoweb.m2catalogue.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.RegisterSupplier;
import lk.coopfed.knoweb.m2catalogue.internal.supplier.RegisterSupplierHandler;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.SupplierView;
import lk.coopfed.knoweb.m2catalogue.web.generated.RegisterSupplierRequest;
import lk.coopfed.knoweb.m2catalogue.web.generated.SupplierApi;
import lk.coopfed.knoweb.m2catalogue.web.generated.SupplierResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** RegisterSupplier and the supplier list (22A section 4, SupplierController). */
@RestController
class SupplierController implements SupplierApi {

    private final RegisterSupplierHandler register;
    private final BatchQueries queries;
    private final CurrentScope currentScope;
    private final ViewPermission view;

    SupplierController(
            RegisterSupplierHandler register,
            BatchQueries queries,
            CurrentScope currentScope,
            PermissionResolver permissions,
            @Value("${coop-erp.security.enforce-permissions:false}") boolean enforcePermissions) {
        this.register = register;
        this.queries = queries;
        this.currentScope = currentScope;
        this.view = new ViewPermission(permissions, enforcePermissions);
    }

    @Override
    public ResponseEntity<List<SupplierResponse>> listSuppliers() {
        ScopeContext scope = currentScope.get();
        view.require(scope);

        return ResponseEntity.ok(queries.listSuppliers(scope).stream()
                .map(SupplierController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<SupplierResponse> registerSupplier(String idempotencyKey, RegisterSupplierRequest request) {
        ScopeContext scope = currentScope.get();

        UUID supplierId = register.handle(new RegisterSupplier(request.getName()), scope);

        SupplierResponse body = queries.listSuppliers(scope).stream()
                .filter(supplier -> supplier.supplierId().equals(supplierId))
                .findFirst()
                .map(SupplierController::toResponse)
                .orElseThrow();

        return ResponseEntity.created(URI.create("/v1/catalogue/suppliers/" + supplierId))
                .body(body);
    }

    private static SupplierResponse toResponse(SupplierView supplier) {
        return new SupplierResponse(
                supplier.supplierId(),
                supplier.ownerEntityId(),
                supplier.name(),
                SupplierResponse.StatusEnum.fromValue(supplier.status()));
    }
}
