package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads the dispute of an invoice (V0005, {@code trading.invoice_dispute}): one insert-only row per
 * act, the invoice disputed while its latest row says DISPUTED. Both parties read every row
 * (party_read), so the seller and the buyer see the same state.
 */
@Component
public class InvoiceDisputes {

    public static final String DISPUTED = "DISPUTED";
    public static final String RESOLVED = "RESOLVED";

    private final JdbcTemplate jdbc;

    InvoiceDisputes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The latest act on the invoice's dispute, if any. */
    public Optional<Latest> latest(UUID invoiceId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                select action, reason from trading.invoice_dispute where invoice_document_id = ?
                 order by recorded_at desc, dispute_event_id desc limit 1
                """,
                invoiceId);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Latest(
                (String) rows.get(0).get("action"), (String) rows.get(0).get("reason")));
    }

    public boolean isDisputed(UUID invoiceId) {
        return latest(invoiceId).map(latest -> DISPUTED.equals(latest.action())).orElse(false);
    }

    public record Latest(String action, String reason) {}
}
