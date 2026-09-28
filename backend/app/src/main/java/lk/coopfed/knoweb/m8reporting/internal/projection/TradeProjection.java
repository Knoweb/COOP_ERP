package lk.coopfed.knoweb.m8reporting.internal.projection;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The trading projections (28A section 3, trade_document_fact, dispute_register and
 * exposure_snapshot in their demo form; doc 28 section 3; M8-04): from M4's events, by the field
 * names of M4's event records.
 *
 * <ul>
 *   <li>{@code trade_document_event}, one row per trading event on a document: the order
 *       (submitted, accepted with its committed ETA, rejected, cancelled), the delivery note
 *       (issued, dispatched), the GRN (confirmed), the invoice (issued with its due date,
 *       disputed, dispute resolved), the credit note (issued), the payment receipt (recorded with
 *       what no invoice took, reversed, its cheque bounced or cleared) and the discrepancy
 *       (raised with its window, settled).
 *   <li>{@code trade_line_fact}, the lines that carry volume: the seller's ACCEPTED order lines and
 *       the buyer's RECEIVED GRN lines, with the quantity the delivery note expected (the fill
 *       rate).
 *   <li>{@code trade_document_link}: the orders a delivery note carries, the GRNs an invoice bills.
 *   <li>{@code trade_settlement_fact}: what a receipt paid of each invoice, what a reversal
 *       reopened (negative), what a credit note credited.
 *   <li>{@code exposure_warning_event}: exposure.warning.v1, with the limit the relationship had.
 * </ul>
 *
 * <p>Each row belongs to the event's owner, the party whose command published it (the buyer
 * submits, confirms the GRN, raises a discrepancy, disputes an invoice; the seller accepts,
 * dispatches, invoices, credits, records payments), and names the other party as the
 * counterparty, who reads it through party_read. Rows are only ever inserted, keyed by the event,
 * so a replay changes nothing.
 *
 * <p>The business date is the date of the event in the business time zone, except where the
 * event names its own: the GRN's confirmedAt, the invoice's tax point, the receipt's receivedOn,
 * the settlement's settledAt.
 */
@Component
public class TradeProjection extends Projection {

    public static final String NAME = "trade";

    private static final Map<String, String[]> KINDS = Map.ofEntries(
            Map.entry("order.submitted.v1", new String[] {"ORDER", "SUBMITTED"}),
            Map.entry("order.accepted.v1", new String[] {"ORDER", "ACCEPTED"}),
            Map.entry("order.rejected.v1", new String[] {"ORDER", "REJECTED"}),
            Map.entry("order.cancelled.v1", new String[] {"ORDER", "CANCELLED"}),
            Map.entry("delivery_note.issued.v1", new String[] {"DELIVERY_NOTE", "ISSUED"}),
            Map.entry("delivery_note.dispatched.v1", new String[] {"DELIVERY_NOTE", "DISPATCHED"}),
            Map.entry("grn.confirmed.v1", new String[] {"GRN", "CONFIRMED"}),
            Map.entry("invoice.issued.v1", new String[] {"INVOICE", "ISSUED"}),
            Map.entry("invoice.disputed.v1", new String[] {"INVOICE", "DISPUTED"}),
            Map.entry("invoice.dispute_resolved.v1", new String[] {"INVOICE", "DISPUTE_RESOLVED"}),
            Map.entry("credit_note.issued.v1", new String[] {"CREDIT_NOTE", "ISSUED"}),
            Map.entry("payment_receipt.recorded.v1", new String[] {"PAYMENT", "RECORDED"}),
            Map.entry("payment_receipt.reversed.v1", new String[] {"PAYMENT", "REVERSED"}),
            // Money on account applied later: a row of its own (the application's id), not the receipt's.
            Map.entry("payment_receipt.applied.v1", new String[] {"PAYMENT", "SETTLED"}),
            Map.entry("cheque.bounced.v1", new String[] {"PAYMENT", "BOUNCED"}),
            Map.entry("cheque.cleared.v1", new String[] {"PAYMENT", "CLEARED"}),
            Map.entry("discrepancy.raised.v1", new String[] {"DISCREPANCY", "RAISED"}),
            Map.entry("discrepancy.settled.v1", new String[] {"DISCREPANCY", "SETTLED"}),
            // Not a document: a row of its own table (exposure_warning_event).
            Map.entry("exposure.warning.v1", new String[] {null, null}));

    private final ZoneId zone;

    TradeProjection(
            JdbcTemplate jdbc, ProjectionStateStore state, @Value("${coop-erp.business-timezone}") String zone) {
        super(NAME, KINDS.keySet(), jdbc, state);
        this.zone = ZoneId.of(zone);
    }

    @EventConsumer(types = "*", consumer = "m8." + NAME)
    @Transactional
    public void on(JsonNode envelope, ScopeContext scope) {
        consume(envelope, scope);
    }

    /** One row of trade_document_event, filled in by the event's case. */
    private static final class Row {
        UUID documentId;
        String number;
        UUID reference;
        LocalDate businessDate;
        BigDecimal net;
        BigDecimal tax;
        BigDecimal gross;
        LocalDate dueDate;
        LocalDate committedEta;
        Instant windowEndsAt;
        BigDecimal unapplied;
    }

    @Override
    protected void apply(ProjectionEvent event, ScopeContext scope) {
        String[] kind = KINDS.get(event.type());
        UUID owner = scope.entityId();
        UUID seller = event.uuid("sellerEntityId");
        UUID buyer = event.uuid("buyerEntityId") != null ? event.uuid("buyerEntityId") : event.uuid("receiverEntityId");
        UUID counterparty = owner.equals(seller) ? buyer : seller;
        JsonNode payload = event.payload();

        if (event.type().equals("exposure.warning.v1")) {
            insertWarning(event, owner, counterparty, seller, buyer);
            return;
        }

        Row row = new Row();
        row.number = event.string("docNumberDisplay");
        row.businessDate = event.occurredAt().atZone(zone).toLocalDate();

        switch (event.type()) {
            case "order.submitted.v1", "order.rejected.v1", "order.cancelled.v1" ->
                row.documentId = event.uuid("orderId");
            case "order.accepted.v1" -> {
                row.documentId = event.uuid("orderId");
                row.committedEta = event.date("committedEta");
                row.net = BigDecimal.ZERO;
                for (JsonNode line : payload.path("lines")) {
                    BigDecimal qty = ProjectionEvent.decimal(line, "allocatedQty");
                    BigDecimal value = value(qty, ProjectionEvent.decimal(line, "tierPrice"));
                    row.net = row.net.add(value == null ? BigDecimal.ZERO : value);
                    insertLine(line, "ACCEPTED", row, owner, counterparty, seller, buyer, qty, null, value);
                }
            }
            case "delivery_note.issued.v1" -> {
                row.documentId = event.uuid("deliveryNoteId");
                for (JsonNode order : payload.path("orderIds")) {
                    insertLink(row.documentId, UUID.fromString(order.asText()), "ORDER", owner, counterparty);
                }
            }
            case "delivery_note.dispatched.v1" -> row.documentId = event.uuid("deliveryNoteId");
            case "grn.confirmed.v1" -> {
                row.documentId = event.uuid("grnId");
                row.reference = event.uuid("deliveryDocumentId");
                if (event.instant("confirmedAt") != null) {
                    row.businessDate = event.instant("confirmedAt").atZone(zone).toLocalDate();
                }
                row.net = BigDecimal.ZERO;
                for (JsonNode line : payload.path("lines")) {
                    BigDecimal qty = ProjectionEvent.decimal(line, "receivedQty");
                    BigDecimal value = value(qty, ProjectionEvent.decimal(line, "unitCost"));
                    row.net = row.net.add(value == null ? BigDecimal.ZERO : value);
                    insertLine(
                            line,
                            "RECEIVED",
                            row,
                            owner,
                            counterparty,
                            seller,
                            buyer,
                            qty,
                            ProjectionEvent.decimal(line, "expectedQty"),
                            value);
                }
            }
            case "invoice.issued.v1" -> {
                row.documentId = event.uuid("invoiceId");
                if (event.date("taxPointDate") != null) {
                    row.businessDate = event.date("taxPointDate");
                }
                row.dueDate = event.date("dueDate");
                row.net = event.decimal("netAmount");
                row.tax = event.decimal("taxAmount");
                row.gross = event.decimal("grossAmount");
                for (JsonNode grn : payload.path("grnIds")) {
                    insertLink(row.documentId, UUID.fromString(grn.asText()), "GRN", owner, counterparty);
                }
            }
            case "invoice.disputed.v1", "invoice.dispute_resolved.v1" -> row.documentId = event.uuid("invoiceId");
            case "credit_note.issued.v1" -> {
                row.documentId = event.uuid("creditNoteId");
                row.reference = event.uuid("invoiceId");
                row.net = event.decimal("netAmount");
                row.tax = event.decimal("taxAmount");
                row.gross = event.decimal("grossAmount");
                if (row.reference != null && row.gross != null) {
                    insertSettlement(
                            row.documentId,
                            row.reference,
                            "CREDIT",
                            owner,
                            counterparty,
                            seller,
                            buyer,
                            row.businessDate,
                            row.gross,
                            event);
                }
            }
            case "payment_receipt.recorded.v1" -> {
                row.documentId = event.uuid("receiptId");
                if (event.date("receivedOn") != null) {
                    row.businessDate = event.date("receivedOn");
                }
                row.gross = event.decimal("amount");
                row.unapplied = event.decimal("unappliedAmount");
                for (JsonNode settlement : payload.path("settlements")) {
                    insertSettlement(
                            row.documentId,
                            ProjectionEvent.uuid(settlement, "invoiceId"),
                            "PAYMENT",
                            owner,
                            counterparty,
                            seller,
                            buyer,
                            row.businessDate,
                            ProjectionEvent.decimal(settlement, "amount"),
                            event);
                }
            }
            case "payment_receipt.applied.v1" -> {
                row.documentId = event.uuid("applicationId");
                row.reference = event.uuid("receiptId");
                if (event.date("appliedOn") != null) {
                    row.businessDate = event.date("appliedOn");
                }
                row.gross = event.decimal("appliedAmount");
                // What the application took off the receipt's money on account.
                row.unapplied = row.gross == null ? null : row.gross.negate();
                for (JsonNode settlement : payload.path("settlements")) {
                    insertSettlement(
                            row.documentId,
                            ProjectionEvent.uuid(settlement, "invoiceId"),
                            "PAYMENT",
                            owner,
                            counterparty,
                            seller,
                            buyer,
                            row.businessDate,
                            ProjectionEvent.decimal(settlement, "amount"),
                            event);
                }
            }
            case "payment_receipt.reversed.v1" -> {
                row.documentId = event.uuid("reversalId");
                row.reference = event.uuid("receiptId");
                row.gross = event.decimal("amount");
                BigDecimal reopened = BigDecimal.ZERO;
                for (JsonNode settlement : payload.path("reopened")) {
                    BigDecimal amount = ProjectionEvent.decimal(settlement, "amount");
                    reopened = reopened.add(amount);
                    insertSettlement(
                            row.documentId,
                            ProjectionEvent.uuid(settlement, "invoiceId"),
                            "REVERSAL",
                            owner,
                            counterparty,
                            seller,
                            buyer,
                            row.businessDate,
                            amount.negate(),
                            event);
                }
                // What the reversal takes back beyond the invoices it reopens was on account.
                row.unapplied = row.gross == null ? null : reopened.subtract(row.gross);
            }
            case "cheque.bounced.v1" -> {
                row.documentId = event.uuid("receiptId");
                row.reference = event.uuid("reversalId");
                row.gross = event.decimal("amount");
            }
            case "cheque.cleared.v1" -> {
                row.documentId = event.uuid("receiptId");
                row.gross = event.decimal("amount");
            }
            case "discrepancy.raised.v1" -> {
                row.documentId = event.uuid("discrepancyId");
                row.reference = event.uuid("grnId");
                row.windowEndsAt = event.instant("windowEndsAt");
            }
            case "discrepancy.settled.v1" -> {
                row.documentId = event.uuid("discrepancyId");
                row.reference = event.uuid("creditNoteId");
                if (event.instant("settledAt") != null) {
                    row.businessDate = event.instant("settledAt").atZone(zone).toLocalDate();
                }
            }
            default -> throw new IllegalStateException("Not a trading event: " + event.type());
        }

        jdbc.update(
                """
                insert into reporting.trade_document_event
                       (document_id, event_kind, owner_entity_id, doc_type, doc_number, counterparty_entity_id,
                        seller_entity_id, buyer_entity_id, relationship_id, reference_document_id, business_date,
                        occurred_at, net, tax, gross, event_id, due_date, committed_eta, window_ends_at, unapplied)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (document_id, event_kind, owner_entity_id) do nothing
                """,
                row.documentId,
                kind[1],
                owner,
                kind[0],
                row.number,
                counterparty,
                seller,
                buyer,
                event.uuid("relationshipId"),
                row.reference,
                Date.valueOf(row.businessDate),
                Timestamp.from(event.occurredAt()),
                row.net,
                row.tax,
                row.gross,
                event.eventId(),
                row.dueDate == null ? null : Date.valueOf(row.dueDate),
                row.committedEta == null ? null : Date.valueOf(row.committedEta),
                row.windowEndsAt == null ? null : Timestamp.from(row.windowEndsAt),
                row.unapplied);
    }

    private void insertLine(
            JsonNode line,
            String measure,
            Row row,
            UUID owner,
            UUID counterparty,
            UUID seller,
            UUID buyer,
            BigDecimal qty,
            BigDecimal expectedQty,
            BigDecimal value) {
        if (qty == null || (qty.signum() == 0 && expectedQty == null)) {
            return;
        }
        jdbc.update(
                """
                insert into reporting.trade_line_fact
                       (line_id, measure, document_id, owner_entity_id, counterparty_entity_id, seller_entity_id,
                        buyer_entity_id, sku_id, business_date, qty, value, expected_qty)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (line_id, measure) do nothing
                """,
                ProjectionEvent.uuid(line, "lineId"),
                measure,
                row.documentId,
                owner,
                counterparty,
                seller,
                buyer,
                ProjectionEvent.uuid(line, "skuId"),
                Date.valueOf(row.businessDate),
                qty,
                value,
                expectedQty);
    }

    private void insertLink(UUID document, UUID linked, String kind, UUID owner, UUID counterparty) {
        jdbc.update(
                """
                insert into reporting.trade_document_link
                       (document_id, linked_document_id, link_kind, owner_entity_id, counterparty_entity_id)
                values (?, ?, ?, ?, ?)
                on conflict (document_id, linked_document_id) do nothing
                """,
                document,
                linked,
                kind,
                owner,
                counterparty);
    }

    private void insertSettlement(
            UUID source,
            UUID invoice,
            String kind,
            UUID owner,
            UUID counterparty,
            UUID seller,
            UUID buyer,
            LocalDate businessDate,
            BigDecimal amount,
            ProjectionEvent event) {
        if (invoice == null || amount == null) {
            return;
        }
        jdbc.update(
                """
                insert into reporting.trade_settlement_fact
                       (source_document_id, invoice_id, kind, owner_entity_id, counterparty_entity_id,
                        seller_entity_id, buyer_entity_id, business_date, amount, event_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (source_document_id, invoice_id, kind) do nothing
                """,
                source,
                invoice,
                kind,
                owner,
                counterparty,
                seller,
                buyer,
                Date.valueOf(businessDate),
                amount,
                event.eventId());
    }

    private void insertWarning(ProjectionEvent event, UUID owner, UUID counterparty, UUID seller, UUID buyer) {
        jdbc.update(
                """
                insert into reporting.exposure_warning_event
                       (event_id, owner_entity_id, counterparty_entity_id, relationship_id, seller_entity_id,
                        buyer_entity_id, amount, credit_limit, threshold_percent, order_id, occurred_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (event_id) do nothing
                """,
                event.eventId(),
                owner,
                counterparty,
                event.uuid("relationshipId"),
                seller,
                buyer,
                event.decimal("amount"),
                event.decimal("creditLimit"),
                event.payload().path("thresholdPercent").asInt(),
                event.uuid("orderId"),
                Timestamp.from(event.occurredAt()));
    }

    /** Quantity times price, to the cent; none when either is missing. */
    private static BigDecimal value(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return null;
        }
        return qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }
}
