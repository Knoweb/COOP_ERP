package lk.coopfed.knoweb.kernel.internal.job;

import java.util.UUID;
import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.ScopedExecution;
import org.springframework.stereotype.Component;

@Component
public class DefaultScopedExecution implements ScopedExecution {

    private final SystemScope systemScope;

    public DefaultScopedExecution(SystemScope systemScope) {
        this.systemScope = systemScope;
    }

    @Override
    public ScopeContext federationView() {
        return SystemScope.federationView();
    }

    @Override
    public ScopeContext own(UUID entityId) {
        return SystemScope.own(entityId, null);
    }

    @Override
    public ScopeContext own(UUID entityId, UUID locationId) {
        return SystemScope.own(entityId, locationId);
    }

    @Override
    public <T> T inScope(ScopeContext scope, Supplier<T> work) {
        return systemScope.inScope(scope, work);
    }

    @Override
    public <T> T inOwnTransaction(ScopeContext scope, Supplier<T> work) {
        return systemScope.inOwnTransaction(scope, work);
    }
}
