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

    static void requireUndecided(JdbcTemplate jdbc, DocumentRecord order) {
        if (!OrderStatus.SUBMITTED.equals(order.status())) {
            throw new ProblemException("m4.order.not_submitted", Map.of("status", order.status()));
        }
        // Locked: two decisions on one order serialise on the seller's allocation key.
        jdbc.queryForList(
                "select pg_advisory_xact_lock(hashtext(?::text))", order.id().toString());
        List<String> decided = jdbc.queryForList(
                "select status from trading.order_allocation where order_id = ?", String.class, order.id());
        if (!decided.isEmpty()) {
            throw new ProblemException("m4.order.already_decided", Map.of("status", decided.get(0)));
        }
    }
}
