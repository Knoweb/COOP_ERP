package lk.coopfed.knoweb.kernel.internal;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Opens the transaction first, applies transaction-local scope second,
 * then runs the command interceptor inside that same transaction.
 */
@Configuration
@EnableTransactionManagement(order = TransactionOrderConfig.TRANSACTION_ORDER)
public class TransactionOrderConfig {

    static final int TRANSACTION_ORDER = Ordered.LOWEST_PRECEDENCE - 100;

    static final int SCOPE_CUSTOMIZER_ORDER = Ordered.LOWEST_PRECEDENCE - 50;

    static final int COMMAND_INTERCEPTOR_ORDER = Ordered.LOWEST_PRECEDENCE - 40;
}
