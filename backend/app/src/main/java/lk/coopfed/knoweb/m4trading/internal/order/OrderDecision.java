package lk.coopfed.knoweb.m4trading.internal.order;

import java.util.List;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.m4trading.internal.queries.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;

/** The seller decides once on a submitted order: accepted or rejected (24A section 6). */
final class OrderDecision {

    private OrderDecision() {}

    /**
     * The order is locked ({@link OrderLocks}), then its status is read again and must still be
     * SUBMITTED, then no decision may exist. The status is read after the lock, not before: the
     * buyer's AmendOrder or CancelOrder holds the same lock while it moves the order, so a
     * decision that waited on it sees the order as the buyer left it (wave 2, M4MONEY-06).
     *
     * @return the order as read under the lock
     */
    static DocumentRecord requireUndecided(JdbcTemplate jdbc, OrderGuards guards, DocumentRecord order) {
        OrderLocks.lock(jdbc, order.id());
        DocumentRecord current = guards.visibleOrder(order.id());
        if (!OrderStatus.SUBMITTED.equals(current.status())) {
            throw new ProblemException("m4.order.not_submitted", Map.of("status", current.status()));
        }
        List<String> decided = jdbc.queryForList(
                "select status from trading.order_allocation where order_id = ?", String.class, order.id());
        if (!decided.isEmpty()) {
            throw new ProblemException("m4.order.already_decided", Map.of("status", decided.get(0)));
        }
        return current;
    }
}
