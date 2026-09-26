package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.sql.SQLException;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m2catalogue.api.ActivateLocalSku;
import lk.coopfed.knoweb.m2catalogue.api.ActivateSharedSku;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DeactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.ReactivateSku;
import lk.coopfed.knoweb.m2catalogue.api.UpdateSku;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Chooses the handler of an operation that has a local and a shared path (22A section 5).
 *
 * <p>The route follows the SKU, never the caller's permissions: an update of a SHARED SKU goes to
 * the shared path (cat.sku.create and the Federation guard), anything else to the local path;
 * an activation goes where its target says. A create always makes a DRAFT, which is neither
 * local nor shared yet, so it has one handler; the path is chosen when the draft is activated.
 */
@Component
public class SkuCommandRouter {

    // A SKU code is eight characters of a 32-letter alphabet; a clash is rare, and a second
    // one in a row rarer still. The bound only stops a broken generator from looping.
    static final int MAX_CREATE_ATTEMPTS = 5;

    private static final String SKU_CODE_CONSTRAINT = "sku_sku_code_key";

    private final CatalogueQueries queries;
    private final CreateLocalSkuHandler create;
    private final UpdateLocalSkuHandler updateLocal;
    private final UpdateSharedSkuHandler updateShared;
    private final ActivateLocalSkuHandler activateLocal;
    private final ActivateSharedSkuHandler activateShared;
    private final DeactivateSkuHandler deactivate;
    private final ReactivateSkuHandler reactivate;

    SkuCommandRouter(
            CatalogueQueries queries,
            CreateLocalSkuHandler create,
            UpdateLocalSkuHandler updateLocal,
            UpdateSharedSkuHandler updateShared,
            ActivateLocalSkuHandler activateLocal,
            ActivateSharedSkuHandler activateShared,
            DeactivateSkuHandler deactivate,
            ReactivateSkuHandler reactivate) {
        this.queries = queries;
        this.create = create;
        this.updateLocal = updateLocal;
        this.updateShared = updateShared;
        this.activateLocal = activateLocal;
        this.activateShared = activateShared;
        this.deactivate = deactivate;
        this.reactivate = reactivate;
    }

    /**
     * Creates the draft. The handler checks that its code is free, but under row-level security
     * it sees only the codes it may read; a code another entity holds surfaces as the unique
     * violation on sku_code, which rolls the whole transaction back (row, audit and event
     * together). The create is then run again with a new code, a bounded number of times.
     */
    public UUID create(CreateSku command, ScopeContext scope) {
        for (int attempt = 1; ; attempt++) {
            try {
                return create.handle(command, scope);
            } catch (DataIntegrityViolationException e) {
                if (!isSkuCodeClash(e)) {
                    throw e;
                }
                if (attempt >= MAX_CREATE_ATTEMPTS) {
                    throw new ProblemException("m2.sku.code_generation_failed");
                }
            }
        }
    }

    public UUID update(UpdateSku command, ScopeContext scope) {
        UUID skuId = command == null ? null : command.skuId();

        // Read under the caller's scope: a SKU it cannot see goes to the local handler, whose
        // guard answers m2.sku.not_found.
        boolean shared = skuId != null
                && queries.getSku(skuId, scope)
                        .map(sku -> Sku.SHARED.equals(sku.status()))
                        .orElse(false);

        return shared ? updateShared.handle(command, scope) : updateLocal.handle(command, scope);
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

    private static boolean isSkuCodeClash(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql
                    && "23505".equals(sql.getSQLState())
                    && sql.getMessage() != null
                    && sql.getMessage().contains(SKU_CODE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }
}
