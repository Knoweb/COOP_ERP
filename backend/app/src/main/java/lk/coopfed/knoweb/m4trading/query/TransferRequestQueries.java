package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The transfer requests (doc 24 section 4.7) the caller's scope reads: a shop session its own
 * requests and those asked of it, the society all of its own; newest first.
 */
public interface TransferRequestQueries {

    Optional<TransferRequestView> getRequest(UUID requestId, ScopeContext scope);

    List<TransferRequestView> listRequests(ScopeContext scope);
}
