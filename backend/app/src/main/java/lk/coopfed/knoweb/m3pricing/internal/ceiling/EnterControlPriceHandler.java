package lk.coopfed.knoweb.m3pricing.internal.ceiling;

import java.math.BigDecimal;
import java.sql.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m3pricing.api.ControlPriceEntered;
import lk.coopfed.knoweb.m3pricing.api.EnterControlPrice;
import lk.coopfed.knoweb.m3pricing.internal.list.Federation;
import lk.coopfed.knoweb.m3pricing.internal.list.PriceListRules;
import lk.coopfed.knoweb.m3pricing.query.ControlPriceView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EnterControlPrice (23A section 7; doc 23 section 4.3 and flow 6.4). Guards, in order: the
 * Federation in an entity-wide OWN scope (the second factor is the permission's, checked by the
 * kernel before the handler runs); the SKU is active and the unit is its base unit (unit conversion
 * of a ceiling is deferred, 23A section 11); the ceiling is above zero with at most two decimals; a
 * gazette reference; the dates in order; no overlap with a ceiling of the same SKU other than the
 * one in force on the first day, which is closed (the exclusion constraint is the backstop).
 * Mutation: the ceiling in force on effective_from, if any, ends at effective_from - 1; the new row
 * is inserted. Nothing is ever edited otherwise, so the table is the history.
 *
 * <p>Not built (deferred): tag scope (M2 publishes no governed-tag query); the urgent change-log
 * rows to every location (the snapshot contributor, M3-09); the gazette review of lists above the
 * new ceiling (M3-08).
 */
@Service
@CommandHandler(permission = "prc.controlprice.enter", requiresMfa = true)
public class EnterControlPriceHandler implements Handles<EnterControlPrice, UUID> {

    static final String AUDIT_ENTERED = "CONTROL_PRICE_ENTERED";

    /** The SKU states in which it can be sold (doc 22 section 4). */
    private static final Set<String> ACTIVE = Set.of("LOCAL", "SHARED");

    private final JdbcTemplate jdbc;
    private final ControlPriceStore store;
    private final CatalogueQueries catalogue;
    private final Federation federation;
    private final AuditFacade audit;
    private final EventPublisher events;

    EnterControlPriceHandler(
            JdbcTemplate jdbc,
            ControlPriceStore store,
            CatalogueQueries catalogue,
            Federation federation,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.store = store;
        this.catalogue = catalogue;
        this.federation = federation;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(EnterControlPrice command, ScopeContext scope) {
        if (command == null
                || command.skuId() == null
                || command.ceilingPrice() == null
                || command.ceilingUomCode() == null
                || command.effectiveFrom() == null) {
            throw new ProblemException("request.invalid");
        }
        PriceListRules.requireOwnerScope(scope);
        if (!federation.isCaller(scope)) {
            throw new ProblemException("m3.control_price.federation_only");
        }
        Optional<SkuView> sku = catalogue.getSku(command.skuId(), scope);
        if (sku.isEmpty() || !ACTIVE.contains(sku.get().status())) {
            throw new ProblemException("m3.control_price.sku_not_active");
        }
        if (!sku.get().baseUomCode().equals(command.ceilingUomCode())) {
            throw new ProblemException(
                    "m3.control_price.uom_invalid", Map.of("uom", sku.get().baseUomCode()));
        }
        BigDecimal ceiling = command.ceilingPrice();
        if (ceiling.signum() <= 0 || ceiling.stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m3.control_price.ceiling_invalid");
        }
        if (command.gazetteReference() == null || command.gazetteReference().isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "gazetteReference"));
        }
        String gazette = command.gazetteReference().strip();
        if (command.effectiveTo() != null && command.effectiveTo().isBefore(command.effectiveFrom())) {
            throw new ProblemException("m3.control_price.dates_invalid");
        }

        // The ceiling in force on the first day is superseded; any other overlap is a mistake.
        List<ControlPriceView> overlapping =
                store.overlapping(command.skuId(), command.effectiveFrom(), command.effectiveTo());
        ControlPriceView superseded = null;
        for (ControlPriceView row : overlapping) {
            if (row.effectiveFrom().isBefore(command.effectiveFrom()) && row.inForce(command.effectiveFrom())) {
                superseded = row;
            } else {
                throw new ProblemException(
                        "m3.control_price.overlap",
                        Map.of("gazetteReference", row.gazetteReference(), "effectiveFrom", row.effectiveFrom()));
            }
        }
        if (superseded != null) {
            jdbc.update(
                    "update pricing.control_price set effective_to = ? where control_price_id = ?",
                    Date.valueOf(command.effectiveFrom().minusDays(1)),
                    superseded.controlPriceId());
        }
        UUID id = Ids.next();
        jdbc.update(
                "insert into pricing.control_price (control_price_id, sku_id, ceiling_price, ceiling_uom_code,"
                        + " effective_from, effective_to, gazette_reference, entered_by, owner_entity_id)"
                        + " values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                command.skuId(),
                ceiling,
                command.ceilingUomCode(),
                Date.valueOf(command.effectiveFrom()),
                command.effectiveTo() == null ? null : Date.valueOf(command.effectiveTo()),
                gazette,
                scope.userId(),
                scope.entityId());

        UUID closed = superseded == null ? null : superseded.controlPriceId();
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("skuId", command.skuId());
        after.put("ceilingPrice", ceiling);
        after.put("ceilingUomCode", command.ceilingUomCode());
        after.put("effectiveFrom", command.effectiveFrom());
        after.put("effectiveTo", command.effectiveTo());
        after.put("gazetteReference", gazette);
        after.put("closes", closed);
        audit.record(AUDIT_ENTERED, Subject.of("control_price", id), null, after, scope);

        events.publish(new ControlPriceEntered(
                id,
                command.skuId(),
                ceiling,
                command.ceilingUomCode(),
                command.effectiveFrom(),
                command.effectiveTo(),
                gazette,
                closed));
        return id;
    }
}
