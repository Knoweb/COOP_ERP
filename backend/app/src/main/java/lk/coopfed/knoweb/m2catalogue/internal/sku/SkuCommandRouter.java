package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DeactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.ReactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.UpdateSku;
import org.springframework.stereotype.Component;

@Component
public class SkuCommandRouter {

    private static final String CREATE_SHARED = "cat.sku.create";

    private final PermissionResolver permissions;
    private final CreateLocalSkuHandler createLocal;
    private final CreateSharedSkuHandler createShared;
    private final UpdateLocalSkuHandler updateLocal;
    private final UpdateSharedSkuHandler updateShared;
    private final ActivateLocalSkuHandler activateLocal;
    private final ActivateSharedSkuHandler activateShared;
    private final DeactivateSkuHandler deactivate;
    private final ReactivateSkuHandler reactivate;

    public SkuCommandRouter(
            PermissionResolver permissions,
            CreateLocalSkuHandler createLocal,
            CreateSharedSkuHandler createShared,
            UpdateLocalSkuHandler updateLocal,
            UpdateSharedSkuHandler updateShared,
            ActivateLocalSkuHandler activateLocal,
            ActivateSharedSkuHandler activateShared,
            DeactivateSkuHandler deactivate,
            ReactivateSkuHandler reactivate) {
        this.permissions = permissions;
        this.createLocal = createLocal;
        this.createShared = createShared;
        this.updateLocal = updateLocal;
        this.updateShared = updateShared;
        this.activateLocal = activateLocal;
        this.activateShared = activateShared;
        this.deactivate = deactivate;
        this.reactivate = reactivate;
    }

    public UUID create(CreateSku command, ScopeContext scope) {
        if (permissions.allows(scope, CREATE_SHARED)) {
            return createShared.handle(command, scope);
        }
        return createLocal.handle(command, scope);
    }

    public UUID update(UpdateSku command, ScopeContext scope) {
        if (permissions.allows(scope, CREATE_SHARED)) {
            return updateShared.handle(command, scope);
        }
        return updateLocal.handle(command, scope);
    }

    public UUID activateLocal(UUID skuId, ScopeContext scope) {
        return activateLocal.handle(new ActivateLocalSku(skuId), scope);
    }

    public UUID activateShared(UUID skuId, ScopeContext scope) {
        return activateShared.handle(new ActivateSharedSku(skuId), scope);
    }

    public UUID deactivate(DeactivateSku command, ScopeContext scope) {
        return deactivate.handle(command, scope);
    }

    public UUID reactivate(ReactivateSku command, ScopeContext scope) {
        return reactivate.handle(command, scope);
    }
}
