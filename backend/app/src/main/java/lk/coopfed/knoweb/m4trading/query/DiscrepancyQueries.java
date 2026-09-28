package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** The discrepancy queries of 24A section 4 (DisputeQueries, demo scope); row-level security decides what a caller sees. */
public interface DiscrepancyQueries {

    Optional<DiscrepancyView> getDiscrepancy(UUID discrepancyId, ScopeContext scope);

    /** The discrepancies the caller's entity raised (BUYER) or that were raised with it (SELLER), newest first. */
    List<DiscrepancyView> listDiscrepancies(OrderQueries.Role role, ScopeContext scope);
}
