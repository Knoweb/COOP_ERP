package lk.coopfed.knoweb.m5inventory.api;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.ScopeContext;

/**
 * The ledger as M5's own commands call it (25A section 6.1): the one place stock changes. It is an
 * internal command (CommandHandler.INTERNAL, CR-19A-6): it runs only inside another command
 * handler, which carries the permission and the idempotency key, and in that command's
 * transaction, so the document and its movements commit or roll back together.
 *
 * <p>Published in {@code api} because the build requires an internal command to be reached
 * through an interface of its module's api package; no other module calls it today.
 */
public interface StockLedger {

    List<PostedMovement> post(PostMovements command, ScopeContext scope);
}
