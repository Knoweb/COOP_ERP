package lk.coopfed.knoweb.kernel.internal.notification;

import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A transaction of its own, in the OWN scope of the notification's entity, for the steps of
 * an attempt that touch the database (the claim, the outcome). It is always a new one: the
 * first attempt runs after the handler's transaction committed, from its after-commit
 * callback, where the committed transaction's resources are still bound to the thread and
 * anything not REQUIRES_NEW would run on them with no commit to follow. The scope on the
 * argument is applied to the connection by the kernel's connection customizer, which looks
 * for a public {@code @Transactional} method with a {@link ScopeContext} argument.
 */
@Component
public class NotificationTransactions {

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public <T> T inOwnScope(ScopeContext scope, Supplier<T> work) {
        return work.get();
    }
}
