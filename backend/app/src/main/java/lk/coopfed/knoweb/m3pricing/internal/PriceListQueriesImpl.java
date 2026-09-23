package lk.coopfed.knoweb.m3pricing.internal;

import lk.coopfed.knoweb.m3pricing.api.PriceListQueries;
import lk.coopfed.knoweb.m3pricing.api.PriceListView;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Queries are read-only transactions that take the {@link ScopeContext}. The parameter looks
 * unused, but it is not: the kernel reads it to put the scope on the transaction, and without
 * it row-level security returns no rows.
 */
@Service
@Transactional(readOnly = true)
class PriceListQueriesImpl implements PriceListQueries {

    private final PriceListRepository repository;

    PriceListQueriesImpl(PriceListRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<PriceListView> find(UUID id, ScopeContext scope) {
        return repository.findById(id).map(PriceList::snapshot);
    }

    @Override
    public List<PriceListView> list(ScopeContext scope) {
        return repository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(PriceList::snapshot)
                .toList();
    }
}
