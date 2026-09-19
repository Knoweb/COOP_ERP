package lk.coopfed.knoweb.kernel.internal.stub;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Fixes the order of the two interceptors around a {@code @Transactional} method. A lower
 * number runs first, that is, further out:
 *
 * <pre>
 *   transaction interceptor (TRANSACTION_ORDER)   opens the transaction
 *     ScopeSessionAspect (SCOPE_ASPECT_ORDER)     sets the scope on that transaction
 *       the method itself
 * </pre>
 *
 * Without this both would have the same default order and Spring could put the aspect
 * outside the transaction, where SET LOCAL has no effect.
 */
@Configuration
@EnableTransactionManagement(order = TransactionOrderConfig.TRANSACTION_ORDER)
public class TransactionOrderConfig {

    static final int TRANSACTION_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    static final int SCOPE_ASPECT_ORDER = Ordered.LOWEST_PRECEDENCE - 50;
}
