package lk.coopfed.knoweb.m8reporting.internal.report;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m8reporting.query.ExceptionItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The exception queue (28A section 7, "exception queue queries" and the EscalationJob; section 8,
 * "Exception queue"), worked out on read from the projections in the caller's scope:
 *
 * <ul>
 *   <li>DISCREPANCY_OPEN (REVIEW): a discrepancy raised and not yet settled; escalated once its
 *       window has ended.
 *   <li>CLAIM_OPEN (REVIEW): a claim raised and not yet decided by the seller (M4-06).
 *   <li>INVOICE_DISPUTED (REVIEW): an invoice disputed and not resolved since.
 *   <li>CHEQUE_BOUNCED (ALERT): a payment whose cheque bounced.
 *   <li>EXPOSURE_WARNING (ALERT): a buyer whose exposure today is at or past the lowest threshold
 *       a warning of the relationship crossed.
 *   <li>NEGATIVE_STOCK (ALERT): a lot below zero, a till sold more than central knew of.
 * </ul>
 *
 * <p>An item is escalated once older than {@code reporting.exception_escalate_after} (P3D, 28A's
 * "unacknowledged > 3 days"). Nothing is stored: 28A's exception_item projection from the audit
 * log, the acknowledgement through the kernel's AUDIT_REVIEWED and the escalation job with its
 * exception_report.generated.v1 wait for the kernel's review command (module README).
 */
@Component
class ExceptionQueue {

    static final String ESCALATE_AFTER = "reporting.exception_escalate_after";

    private final JdbcTemplate jdbc;
    private final ConfigRegistry config;
    private final Clock clock;

    ExceptionQueue(JdbcTemplate jdbc, ConfigRegistry config, Clock clock) {
        this.jdbc = jdbc;
        this.config = config;
        this.clock = clock;
    }

    List<ExceptionItem> items(ScopeContext scope, Names names) {
        Instant now = clock.instant();
        Duration after = config.getDuration(ESCALATE_AFTER, scope, Duration.ofDays(3));
        Instant escalateBefore = now.minus(after);
        UUID me = scope.entityId();
        List<ExceptionItem> items = new ArrayList<>();

        jdbc.query(
                """
                select r.document_id, r.doc_number, r.seller_entity_id, r.buyer_entity_id, r.occurred_at,
                       r.window_ends_at, r.qty_at_issue
                  from (select distinct on (document_id) * from reporting.trade_document_event
                         where doc_type = 'DISCREPANCY' and event_kind = 'RAISED'
                         order by document_id, owner_entity_id) r
                 where not exists (select 1 from reporting.trade_document_event s
                                    where s.document_id = r.document_id and s.event_kind = 'SETTLED')
                """,
                rs -> {
                    Instant since = rs.getTimestamp("occurred_at").toInstant();
                    Timestamp window = rs.getTimestamp("window_ends_at");
                    boolean escalated = since.isBefore(escalateBefore)
                            || (window != null && window.toInstant().isBefore(now));
                    items.add(trading(
                            "DISCREPANCY_OPEN",
                            "REVIEW",
                            rs.getObject("document_id", UUID.class),
                            rs.getString("doc_number"),
                            rs.getObject("seller_entity_id", UUID.class),
                            rs.getObject("buyer_entity_id", UUID.class),
                            // The quantity at issue (V0006), not money: the event carries no price. The
                            // screen shows it as a quantity, as it does for NEGATIVE_STOCK.
                            rs.getBigDecimal("qty_at_issue"),
                            null,
                            since,
                            escalated,
                            me,
                            names));
                });

        // M4-06: a claim raised and not yet decided by the seller; escalated once its window has ended.
        jdbc.query(
                """
                select r.document_id, r.doc_number, r.seller_entity_id, r.buyer_entity_id, r.occurred_at,
                       r.window_ends_at
                  from (select distinct on (document_id) * from reporting.trade_document_event
                         where doc_type = 'CLAIM' and event_kind = 'RAISED'
                         order by document_id, owner_entity_id) r
                 where not exists (select 1 from reporting.trade_document_event s
                                    where s.document_id = r.document_id
                                      and s.event_kind in ('APPROVED', 'REJECTED'))
                """,
                rs -> {
                    Instant since = rs.getTimestamp("occurred_at").toInstant();
                    Timestamp window = rs.getTimestamp("window_ends_at");
                    boolean escalated = since.isBefore(escalateBefore)
                            || (window != null && window.toInstant().isBefore(now));
                    items.add(trading(
                            "CLAIM_OPEN",
                            "REVIEW",
                            rs.getObject("document_id", UUID.class),
                            rs.getString("doc_number"),
                            rs.getObject("seller_entity_id", UUID.class),
                            rs.getObject("buyer_entity_id", UUID.class),
                            null,
                            null,
                            since,
                            escalated,
                            me,
                            names));
                });

        jdbc.query(
                """
                select d.document_id, i.doc_number, d.seller_entity_id, d.buyer_entity_id, d.occurred_at, i.gross
                  from (select distinct on (document_id) * from reporting.trade_document_event
                         where doc_type = 'INVOICE' and event_kind = 'DISPUTED'
                         order by document_id, occurred_at desc) d
                  left join (select distinct on (document_id) document_id, doc_number, gross
                               from reporting.trade_document_event
                              where doc_type = 'INVOICE' and event_kind = 'ISSUED'
                              order by document_id) i on i.document_id = d.document_id
                 where not exists (select 1 from reporting.trade_document_event x
                                    where x.document_id = d.document_id and x.event_kind = 'DISPUTE_RESOLVED'
                                      and x.occurred_at >= d.occurred_at)
                """,
                rs -> {
                    Instant since = rs.getTimestamp("occurred_at").toInstant();
                    items.add(trading(
                            "INVOICE_DISPUTED",
                            "REVIEW",
                            rs.getObject("document_id", UUID.class),
                            rs.getString("doc_number"),
                            rs.getObject("seller_entity_id", UUID.class),
                            rs.getObject("buyer_entity_id", UUID.class),
                            rs.getBigDecimal("gross"),
                            null,
                            since,
                            since.isBefore(escalateBefore),
                            me,
                            names));
                });

        jdbc.query(
                """
                select b.document_id, p.doc_number, b.seller_entity_id, b.buyer_entity_id, b.occurred_at, b.gross
                  from (select distinct on (document_id) * from reporting.trade_document_event
                         where doc_type = 'PAYMENT' and event_kind = 'BOUNCED'
                         order by document_id, owner_entity_id) b
                  left join (select distinct on (document_id) document_id, doc_number
                               from reporting.trade_document_event
                              where doc_type = 'PAYMENT' and event_kind = 'RECORDED'
                              order by document_id) p on p.document_id = b.document_id
                """,
                rs -> {
                    Instant since = rs.getTimestamp("occurred_at").toInstant();
                    items.add(trading(
                            "CHEQUE_BOUNCED",
                            "ALERT",
                            rs.getObject("document_id", UUID.class),
                            rs.getString("doc_number"),
                            rs.getObject("seller_entity_id", UUID.class),
                            rs.getObject("buyer_entity_id", UUID.class),
                            rs.getBigDecimal("gross"),
                            null,
                            since,
                            since.isBefore(escalateBefore),
                            me,
                            names));
                });

        jdbc.query(
                "select * from " + TradeSql.EXPOSURE + " e"
                        + " where e.exposure * 100 >= e.credit_limit * e.threshold_percent",
                rs -> {
                    Instant since = rs.getTimestamp("warned_at").toInstant();
                    BigDecimal exposure = rs.getBigDecimal("exposure");
                    items.add(trading(
                            "EXPOSURE_WARNING",
                            "ALERT",
                            rs.getObject("relationship_id", UUID.class),
                            null,
                            rs.getObject("seller_entity_id", UUID.class),
                            rs.getObject("buyer_entity_id", UUID.class),
                            ReportSources.money(exposure),
                            ReportSources.percent(exposure, rs.getBigDecimal("credit_limit")),
                            since,
                            since.isBefore(escalateBefore),
                            me,
                            names));
                });

        jdbc.query(
                """
                select batch_id, location_id, canonical_sku_id, qty_on_hand, freshness
                  from reporting.stock_position where qty_on_hand < 0
                """,
                rs -> {
                    Instant since = rs.getTimestamp("freshness").toInstant();
                    UUID sku = rs.getObject("canonical_sku_id", UUID.class);
                    items.add(new ExceptionItem(
                            "NEGATIVE_STOCK",
                            "ALERT",
                            rs.getObject("batch_id", UUID.class),
                            null,
                            null,
                            null,
                            null,
                            names.skuName(sku) + " (" + names.skuCode(sku) + "), "
                                    + names.location(rs.getObject("location_id", UUID.class)),
                            rs.getBigDecimal("qty_on_hand"),
                            null,
                            since,
                            since.isBefore(escalateBefore)));
                });

        items.sort(Comparator.comparing((ExceptionItem item) -> !item.escalated())
                .thenComparing(item -> !"ALERT".equals(item.severity()))
                .thenComparing(ExceptionItem::since));
        return items;
    }

    private static ExceptionItem trading(
            String kind,
            String severity,
            UUID subject,
            String number,
            UUID seller,
            UUID buyer,
            BigDecimal amount,
            BigDecimal percent,
            Instant since,
            boolean escalated,
            UUID me,
            Names names) {
        // The caller's side: a buyer sees its seller, anybody else (the seller, the Federation
        // view) sees the buyer.
        boolean buyerSide = me != null && me.equals(buyer) && !me.equals(seller);
        UUID counterparty = buyerSide ? seller : buyer;
        return new ExceptionItem(
                kind,
                severity,
                subject,
                number,
                buyerSide ? "BUYER" : "SELLER",
                counterparty,
                counterparty == null ? null : names.entity(counterparty),
                null,
                amount,
                percent,
                since,
                escalated);
    }
}
