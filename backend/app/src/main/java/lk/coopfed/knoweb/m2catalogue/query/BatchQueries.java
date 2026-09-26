package lk.coopfed.knoweb.m2catalogue.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * ListBatches / GetBatch of 22A section 7 ("by sku, batchNo, expiring window; status"), and the
 * supplier list of 22A section 5 (GET /v1/catalogue/suppliers). A batch is global identity, read
 * by every scope; a supplier is read by every scope too (the batch carries it, V0004).
 */
public interface BatchQueries {

    Optional<BatchView> getBatch(UUID batchId, ScopeContext scope);

    /** The batches of one SKU, newest first; SUPERSEDED ones included, with the batch they correct. */
    List<BatchView> listBatches(BatchFilter filter, ScopeContext scope);

    /** The suppliers of the caller's entity, by name. */
    List<SupplierView> listSuppliers(ScopeContext scope);
}
