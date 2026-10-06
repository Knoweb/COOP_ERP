package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** ListClaims and GetClaim (doc 24 section 5.2, demo scope); row-level security decides what a caller sees. */
public interface ClaimQueries {

    Optional<ClaimView> getClaim(UUID claimId, ScopeContext scope);

    /** The claims the caller's entity raised (BUYER) or that were raised with it (SELLER), newest first. */
    List<ClaimView> listClaims(OrderQueries.Role role, ScopeContext scope);
}
