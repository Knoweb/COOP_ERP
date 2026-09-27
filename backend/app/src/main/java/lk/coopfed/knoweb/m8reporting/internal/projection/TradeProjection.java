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
 * The trading projections of the demo (28A section 3, trade_document_fact, and doc 28 section
 * 3; M8-06 in part): {@code trade_document_event}, one row per trading event on a document, and
 * {@code trade_line_fact}, the lines that carry volume, from M4's events (the field names of
 * M4's event records: OrderSubmitted, OrderAccepted, OrderRejected, OrderCancelled,
 * DeliveryNoteDispatched, GrnConfirmed, InvoiceIssued).
 *
 * <p>Each row belongs to the event's owner, the party whose command published it (the buyer
 * submits, confirms the GRN; the seller accepts, dispatches, invoices), and names the other
 * party as the counterparty, who reads it through party_read. Rows are only ever inserted, keyed
 * by the event (document, kind, owner) or the line (line, measure), so a replay changes nothing.
 *
 * <p>Volume: the seller's accepted order lines (allocated quantity at the tier price) and the
 * buyer's received GRN lines (received quantity at the unit cost the GRN carries, the trade
 * price). The GRN is where ownership passes (AGENTS.md idea 2), so "trade" in the reports is
 * RECEIVED unless a report says otherwise.
 *
 * <p>The business date is the date of the event in the business time zone (the GRN's
 * confirmedAt, the invoice's tax point date, otherwise the time the event happened).
 */
@Component
public class TradeProjection extends Projection {

    public static final String NAME = "trade";

    private static final Map<String, String[]> KINDS = Map.of(
            "order.submitted.v1", new String[] {"ORDER", "SUBMITTED"},
            "order.accepted.v1", new String[] {"ORDER", "ACCEPTED"},
            "order.rejected.v1", new String[] {"ORDER", "REJECTED"},
            "order.cancelled.v1", new String[] {"ORDER", "CANCELLED"},
            "delivery_note.dispatched.v1", new String[] {"DELIVERY_NOTE", "DISPATCHED"},
            "grn.confirmed.v1", new String[] {"GRN", "CONFIRMED"},
            "invoice.issued.v1", new String[] {"INVOICE", "ISSUED"});

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

    @Override
    protected void apply(ProjectionEvent event, ScopeContext scope) {
        String[] kind = KINDS.get(event.type());
        UUID owner = scope.entityId();
        UUID seller = event.uuid("sellerEntityId");
        UUID buyer = event.uuid(event.type().startsWith("grn.") ? "receiverEntityId" : "buyerEntityId");
        UUID counterparty = owner.equals(seller) ? buyer : seller;
        LocalDate businessDate = businessDate(event);
        JsonNode payload = event.payload();

        UUID documentId;
        String number = event.text("docNumberDisplay");
        UUID reference = null;
        BigDecimal net = null;
        BigDecimal tax = null;
        BigDecimal gross = null;

        switch (event.type()) {
            case "order.submitted.v1", "order.accepted.v1", "order.rejected.v1", "order.cancelled.v1" -> {
                documentId = event.uuid("orderId");
                if (event.type().equals("order.accepted.v1")) {
                    net = BigDecimal.ZERO;
                    for (JsonNode line : payload.path("lines")) {
                        BigDecimal value = value(
                                ProjectionEvent.decimal(line, "allocatedQty"),
                                ProjectionEvent.decimal(line, "tierPrice"));
                        net = net.add(value == null ? BigDecimal.ZERO : value);
                        insertLine(
                                line,
                                "ACCEPTED",
                                documentId,
                                owner,
                                counterparty,
                                seller,
                                buyer,
                                businessDate,
                                ProjectionEvent.decimal(line, "allocatedQty"),
                                value);
                    }
                }
            }
            case "delivery_note.dispatched.v1" -> documentId = event.uuid("deliveryNoteId");
            case "grn.confirmed.v1" -> {
                documentId = event.uuid("grnId");
                reference = event.uuid("deliveryDocumentId");
                net = BigDecimal.ZERO;
                for (JsonNode line : payload.path("lines")) {
                    BigDecimal value = value(
                            ProjectionEvent.decimal(line, "receivedQty"), ProjectionEvent.decimal(line, "unitCost"));
                    net = net.add(value == null ? BigDecimal.ZERO : value);
                    insertLine(
                            line,
                            "RECEIVED",
                            documentId,
                            owner,
                            counterparty,
                            seller,
                            buyer,
                            businessDate,
                            ProjectionEvent.decimal(line, "receivedQty"),
                            value);
                }
            }
            case "invoice.issued.v1" -> {
                documentId = event.uuid("invoiceId");
                net = event.decimal("netAmount");
                tax = event.decimal("taxAmount");
                gross = event.decimal("grossAmount");
            }
            default -> throw new IllegalStateException("Not a trading event: " + event.type());
        }

        jdbc.update(
                """
                insert into reporting.trade_document_event
                       (document_id, event_kind, owner_entity_id, doc_type, doc_number, counterparty_entity_id,
                        seller_entity_id, buyer_entity_id, relationship_id, reference_document_id, business_date,
                        occurred_at, net, tax, gross, event_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (document_id, event_kind, owner_entity_id) do nothing
                """,
                documentId,
                kind[1],
                owner,
                kind[0],
                number,
                counterparty,
                seller,
                buyer,
                event.uuid("relationshipId"),
                reference,
                Date.valueOf(businessDate),
                Timestamp.from(event.occurredAt()),
                net,
                tax,
                gross,
                event.eventId());
    }

    private void insertLine(
            JsonNode line,
            String measure,
            UUID documentId,
            UUID owner,
            UUID counterparty,
            UUID seller,
            UUID buyer,
            LocalDate businessDate,
            BigDecimal qty,
            BigDecimal value) {
        if (qty == null || qty.signum() == 0) {
            return;
        }
        jdbc.update(
                """
                insert into reporting.trade_line_fact
                       (line_id, measure, document_id, owner_entity_id, counterparty_entity_id, seller_entity_id,
                        buyer_entity_id, sku_id, business_date, qty, value)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (line_id, measure) do nothing
                """,
                ProjectionEvent.uuid(line, "lineId"),
                measure,
                documentId,
                owner,
                counterparty,
                seller,
                buyer,
                ProjectionEvent.uuid(line, "skuId"),
                Date.valueOf(businessDate),
                qty,
                value);
    }

    private LocalDate businessDate(ProjectionEvent event) {
        if (event.type().equals("invoice.issued.v1") && event.date("taxPointDate") != null) {
            return event.date("taxPointDate");
        }
        Instant at = event.type().equals("grn.confirmed.v1") && event.instant("confirmedAt") != null
                ? event.instant("confirmedAt")
                : event.occurredAt();
        return at.atZone(zone).toLocalDate();
    }

    /** Quantity times price, to the cent; none when either is missing. */
    private static BigDecimal value(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return null;
        }
        return qty.multiply(price).setScale(2, RoundingMode.HALF_UP);
    }
}
