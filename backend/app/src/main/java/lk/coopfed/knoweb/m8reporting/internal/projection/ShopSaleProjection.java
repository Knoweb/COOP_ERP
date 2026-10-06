package lk.coopfed.knoweb.m8reporting.internal.projection;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The shop's sales (28A section 3, receipt_fact and receipt_line_fact, demo part; doc 28 section
 * 3, "receipt bundles"): {@code shop_sale_fact}, one row per receipt a till issued, and
 * {@code shop_sale_line_fact}, one per line, from the till's own {@code receipt.issued.v1} (the
 * bundle of doc 32 section 3.1, {@code document} and {@code lines} with doc 18's column names; M8
 * imports nothing of M6, as M5 and M6 read the same bundle).
 *
 * <p>The dispatcher hands the bundle over in the OWN scope of the device's entity at its shop, so
 * each row is the shop's and only its owner and the Federation view read it (no party_read).
 * The business date is the till's own ({@code business_date}), otherwise the day it was issued.
 * Only ever inserted, keyed by the receipt and the line; no cost (the till does not send one).
 */
@Component
public class ShopSaleProjection extends Projection {

    public static final String NAME = "shop_sales";

    static final String RECEIPT_ISSUED = "receipt.issued.v1";

    private final ZoneId zone;

    ShopSaleProjection(
            JdbcTemplate jdbc, ProjectionStateStore state, @Value("${coop-erp.business-timezone}") String zone) {
        super(NAME, Set.of(RECEIPT_ISSUED), jdbc, state);
        this.zone = ZoneId.of(zone);
    }

    @EventConsumer(types = "*", consumer = "m8." + NAME)
    @Transactional
    public void on(JsonNode envelope, ScopeContext scope) {
        consume(envelope, scope);
    }

    @Override
    protected void apply(ProjectionEvent event, ScopeContext scope) {
        JsonNode document = event.payload().path("document");
        UUID receipt = ProjectionEvent.uuid(document, "document_id");
        // The device's shop, as M6 files the receipt (wave 2, M8-04): the till's own location_id
        // only when the scope has none.
        UUID location = scope.locationId();
        if (location == null) {
            location = ProjectionEvent.uuid(document, "location_id");
        }
        String day = ProjectionEvent.string(document, "business_date");
        String issued = ProjectionEvent.string(document, "issued_at");
        LocalDate businessDate = day != null
                ? LocalDate.parse(day)
                : (issued != null ? java.time.Instant.parse(issued) : event.occurredAt())
                        .atZone(zone)
                        .toLocalDate();
        int lines = 0;
        for (JsonNode line : event.payload().path("lines")) {
            BigDecimal qty = ProjectionEvent.decimal(line, "qty");
            UUID sku = ProjectionEvent.uuid(line, "sku_id");
            // The line's number is its identity (doc 32's bundle, M6's receipt_line key); the till
            // sends no line_id (wave 2, M8-04), which is kept only when a bundle carries one.
            int lineNo = line.path("line_no").asInt(0);
            if (qty == null || sku == null || lineNo < 1) {
                continue;
            }
            lines++;
            jdbc.update(
                    """
                    insert into reporting.shop_sale_line_fact
                           (receipt_id, line_no, line_id, owner_entity_id, location_id, business_date, sku_id, qty,
                            line_total)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (receipt_id, line_no) do nothing
                    """,
                    receipt,
                    lineNo,
                    ProjectionEvent.uuid(line, "line_id"),
                    scope.entityId(),
                    location,
                    Date.valueOf(businessDate),
                    sku,
                    qty,
                    ProjectionEvent.decimal(line, "line_total"));
        }
        jdbc.update(
                """
                insert into reporting.shop_sale_fact
                       (receipt_id, owner_entity_id, location_id, business_date, doc_number, net, tax, gross, lines,
                        occurred_at, event_id)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (receipt_id) do nothing
                """,
                receipt,
                scope.entityId(),
                location,
                Date.valueOf(businessDate),
                ProjectionEvent.string(document, "doc_number_display"),
                ProjectionEvent.decimal(document, "net_amount"),
                ProjectionEvent.decimal(document, "tax_amount"),
                ProjectionEvent.decimal(document, "gross_amount"),
                lines,
                Timestamp.from(event.occurredAt()),
                event.eventId());
    }
}
