package lk.coopfed.knoweb.m4trading.internal.order;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One advisory lock per order, shared by both parties (wave 2, M4MONEY-06 and -07; decision (3) of
 * {@code docs/progress/deviations/2026-10-06-wave2-payments-and-cheques.md}). The buyer's order is
 * its document, and the seller's decision and dispatch are the seller's own rows; the seller cannot
 * row-lock the buyer's document (under {@code kernel.document}'s {@code own_update} policy a
 * {@code SELECT ... FOR UPDATE} by the seller returns nothing), so both sides meet on this key
 * instead, before they read the order's status or the seller's allocation:
 *
 * <ul>
 *   <li>AcceptOrder and RejectOrder take it, then re-read the order's status;
 *   <li>AmendOrder and CancelOrder take it after the buyer's own row lock on its document, then
 *       read the allocation (and, to cancel, what was dispatched);
 *   <li>IssueDeliveryNote takes it for every order on the note, in sorted order ({@link
 *       #lockAll}), before it reads the orders and locks its allocation lines.
 * </ul>
 *
 * <p>Why this cannot deadlock: Accept, Reject, Amend and Cancel each hold one order lock; only
 * IssueDeliveryNote holds several, always in the same sorted order. The key space ({@code
 * hashtext}, int4) is not the kernel's {@code lockForLinking} ({@code hashtextextended}, bigint),
 * so the two never meet; a {@code hashtext} collision between two orders only serialises them.
 * The lock is transaction-scoped and released at commit or rollback.
 */
public final class OrderLocks {

    private OrderLocks() {}

    /** Locks one order for the rest of the transaction. */
    public static void lock(JdbcTemplate jdbc, UUID orderId) {
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "order-" + orderId);
    }

    /** Locks several orders, each once, in UUID order, so two transactions never wait on each other in a ring. */
    public static void lockAll(JdbcTemplate jdbc, Collection<UUID> orderIds) {
        for (UUID orderId : sorted(orderIds)) {
            lock(jdbc, orderId);
        }
    }

    /** The distinct ids in the order the locks are taken. */
    static List<UUID> sorted(Collection<UUID> orderIds) {
        return orderIds.stream().distinct().sorted().toList();
    }
}
