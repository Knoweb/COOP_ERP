package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The GRN queries of doc 24 section 5.2; row-level security decides what a caller sees. */
public interface GrnQueries {

    Optional<GrnView> getGrn(UUID grnId, ScopeContext scope);

    /**
     * The GRNs the caller's entity received (BUYER; at the session's location when it has one) or
     * that were received from it (SELLER, issued only), newest first.
     */
    List<GrnView> listGrns(OrderQueries.Role role, ScopeContext scope);
}
