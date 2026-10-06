package lk.coopfed.knoweb.m7customers.query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The data-subject requests of the caller's society (27A section 8, "Privacy requests"). The
 * access export is not a read of this interface: it is handed over by the command {@code
 * DownloadAccessExport}, so that the hand-over is the responsible officer's and audited (doc 27
 * section 9.3; wave 2, M7CR-11).
 */
public interface PrivacyQueries {

    /** The society's requests, newest first; {@code status} null for all of them. */
    List<RequestView> requests(String status, ScopeContext scope);

    Optional<RequestView> request(UUID requestId, ScopeContext scope);

    /**
     * @param customerName the customer's name as it is now ("Customer" once anonymised)
     * @param outcome      what the officer did; for a refusal, see {@code refusalGround}
     */
    record RequestView(
            UUID requestId,
            UUID customerId,
            String customerName,
            String kind,
            String notes,
            Instant receivedAt,
            UUID receivedBy,
            String status,
            Instant answeredAt,
            UUID answeredBy,
            String outcome,
            String exportSha256,
            String refusalGround) {}
}
