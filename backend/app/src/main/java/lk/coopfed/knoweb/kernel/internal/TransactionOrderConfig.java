package lk.coopfed.knoweb.kernel.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Ensures the transaction interceptor opens the transaction before the scope
 * connection customizer executes SET LOCAL on that same transaction.
 */
@Configuration
@EnableTransactionManagement(order = TransactionOrderConfig.TRANSACTION_ORDER)
public class TransactionOrderConfig {

    static final int TRANSACTION_ORDER =
            Ordered.LOWEST_PRECEDENCE - 100;

    static final int SCOPE_CUSTOMIZER_ORDER =
            Ordered.LOWEST_PRECEDENCE - 50;
}
