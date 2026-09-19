package lk.coopfed.knoweb.hello.internal;

import lk.coopfed.knoweb.hello.api.GreetingQueries;
import lk.coopfed.knoweb.hello.api.GreetingView;
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
class GreetingQueriesImpl implements GreetingQueries {

    private final GreetingRepository repository;

    GreetingQueriesImpl(GreetingRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<GreetingView> find(UUID id, ScopeContext scope) {
        return repository.findById(id).map(Greeting::snapshot);
    }

    @Override
    public List<GreetingView> list(ScopeContext scope) {
        return repository.findTop100ByOrderByCreatedAtDesc().stream()
                .map(Greeting::snapshot)
                .toList();
    }
}
