package lk.coopfed.knoweb.m4trading.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/** Read-only queries for inter-entity opening receivables (CAT-12). */
public interface OpeningReceivableQueries {

    Optional<OpeningReceivableView> getOpeningReceivable(UUID documentId, ScopeContext scope);

    List<OpeningReceivableView> listOpeningReceivables(UUID counterpartyId, ScopeContext scope);
}
