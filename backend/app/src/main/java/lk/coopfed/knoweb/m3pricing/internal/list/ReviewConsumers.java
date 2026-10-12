package lk.coopfed.knoweb.m3pricing.internal.list;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.AuditReviewReader;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.ScopedExecution;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistered;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import lk.coopfed.knoweb.m3pricing.api.ReviewRaised;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ReviewConsumers {

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;
    private final ScopedExecution execution;
    private final AuditReviewReader auditReviews;

    public ReviewConsumers(
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone,
            ScopedExecution execution,
            AuditReviewReader auditReviews) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = businessZone != null ? ZoneId.of(businessZone) : ZoneId.systemDefault();
        this.execution = execution;
        this.auditReviews = auditReviews;
    }

    private record Breach(UUID ownerId, BigDecimal price, String uomCode, String kind) {}

    @EventConsumer(
            types = {"batch.registered.v1"},
            consumer = "m3.batch-mrp-review")
    public void onBatchRegistered(BatchRegistered event, ScopeContext scope) {
        if (event.printedMrp() == null) {
            return;
        }

        List<Breach> breaches = execution.inOwnTransaction(execution.federationView(), () -> {
            return jdbc.query(
                    "select pl.owner_entity_id, max(l.price) as price, min(l.uom_code) as uom_code "
                            + "from pricing.price_list_line l "
                            + "join pricing.price_list pl on pl.price_list_id = l.price_list_id "
                            + "where l.sku_id = ? and pl.kind = 'RETAIL' and pl.status = 'PUBLISHED' and l.price > ? "
                            + "group by pl.owner_entity_id",
                    (rs, rowNum) -> new Breach(
                            UUID.fromString(rs.getString("owner_entity_id")),
                            rs.getBigDecimal("price"),
                            rs.getString("uom_code"),
                            "RETAIL"),
                    event.skuId(),
                    event.printedMrp());
        });

        for (Breach breach : breaches) {
            ScopeContext ownerScope = execution.own(breach.ownerId());
            execution.inOwnTransaction(ownerScope, () -> {
                if (auditReviews.reviewExists(
                        "BATCH_MRP_BELOW_LIST",
                        event.skuId(),
                        "batchId",
                        event.batchId().toString(),
                        ownerScope)) {
                    return null;
                }

                Map<String, Object> details = new LinkedHashMap<>();
                details.put("batchId", event.batchId());
                details.put("batchNo", event.batchNo());
                details.put("printedMrp", event.printedMrp());
                details.put("listPrice", breach.price());

                audit.record("BATCH_MRP_BELOW_LIST", Subject.of("sku", event.skuId()), null, details, ownerScope);
                events.publish(
                        new ReviewRaised("BATCH_MRP_BELOW_LIST", event.skuId(), breach.ownerId(), event.batchNo()));
                return null;
            });
        }
    }

    @EventConsumer(
            types = {"control_price.entered.v1"},
            consumer = "m3.gazette-review")
    public void onControlPriceEntered(ControlPriceEntered event, ScopeContext scope) {
        List<Breach> breaches = execution.inOwnTransaction(execution.federationView(), () -> {
            return jdbc.query(
                    "select pl.owner_entity_id, pl.kind, max(l.price) as price, min(l.uom_code) as uom_code "
                            + "from pricing.price_list_line l "
                            + "join pricing.price_list pl on pl.price_list_id = l.price_list_id "
                            + "where l.sku_id = ? and pl.status = 'PUBLISHED' and l.price > ? "
                            + "group by pl.owner_entity_id, pl.kind",
                    (rs, rowNum) -> new Breach(
                            UUID.fromString(rs.getString("owner_entity_id")),
                            rs.getBigDecimal("price"),
                            rs.getString("uom_code"),
                            rs.getString("kind")),
                    event.skuId(),
                    event.ceilingPrice());
        });

        for (Breach breach : breaches) {
            ScopeContext ownerScope = execution.own(breach.ownerId());
            execution.inOwnTransaction(ownerScope, () -> {
                if (auditReviews.reviewExists(
                        "LIST_ABOVE_CEILING_AT_GAZETTE",
                        event.skuId(),
                        "controlPriceId",
                        event.controlPriceId().toString(),
                        ownerScope)) {
                    return null;
                }

                Map<String, Object> details = new LinkedHashMap<>();
                details.put("controlPriceId", event.controlPriceId());
                details.put("gazetteReference", event.gazetteReference());
                details.put("ceilingPrice", event.ceilingPrice());
                details.put("listKind", breach.kind());
                details.put("listPrice", breach.price());

                audit.record(
                        "LIST_ABOVE_CEILING_AT_GAZETTE", Subject.of("sku", event.skuId()), null, details, ownerScope);
                events.publish(new ReviewRaised(
                        "LIST_ABOVE_CEILING_AT_GAZETTE", event.skuId(), breach.ownerId(), event.gazetteReference()));
                return null;
            });
        }
    }
}
