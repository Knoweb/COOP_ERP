package lk.coopfed.knoweb.testsupport;

import java.util.function.Supplier;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.transaction.annotation.Transactional;

/**
 * A command that runs whatever a test gives it, the way M4's GRN confirmation will run M2's
 * RegisterBatch: an internal command (CommandHandler.INTERNAL, CR-19A-6) runs only inside
 * another command handler, so a test of one calls it through this.
 *
 * <pre>
 *   RegisteredBatch batch = outer.run(scope, () -&gt; registration.register(command, scope));
 * </pre>
 *
 * The permission is a test code: permissions are not enforced in the test context unless a test
 * switches them on. Not a component, so that no other context picks it up;
 * {@link PostgresIntegrationTest} imports it.
 */
@CommandHandler(permission = "test.outer.run")
public class OuterCommand {

    @Transactional
    public <T> T run(ScopeContext scope, Supplier<T> body) {
        return body.get();
    }
}
