package lk.coopfed.knoweb.m2catalogue.internal.unit;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.ConversionDefined;
import lk.coopfed.knoweb.m2catalogue.api.DefineConversion;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuAccess;
import lk.coopfed.knoweb.m2catalogue.internal.sku.SkuIdentity;
import lk.coopfed.knoweb.m2catalogue.internal.unit.ConversionStore.ConversionRow;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DefineConversion (22A section 6): owner; the unit exists; the factor is positive; a weighed SKU
 * takes no count unit; the exclusion constraint of catalogue.sku_uom_conversion keeps the
 * effective ranges of (sku, unit) apart. Mutation: the open-ended row of the same unit, if any,
 * closes the day before the new one starts (doc 22 section 3.2: a case-size change is a new
 * effective-dated row), and the new row is inserted.
 *
 * <p>The permission is the owner's SKU-definition permission, cat.sku.create_local: 22A section
 * 3.1 names no conversion code among its eleven, and doc 22 section 5.1 says only "owner". The
 * shared_read policy lets everyone read the row afterwards, as with the SKU itself.
 */
@Service
@CommandHandler(permission = "cat.sku.create_local")
public class DefineConversionHandler implements Handles<DefineConversion, UUID> {

    static final String AUDIT_DEFINED = "CONVERSION_DEFINED";

    /** PostgreSQL: exclusion_violation. */
    private static final String EXCLUSION_VIOLATION = "23P01";

    private final SkuAccess skus;
    private final ConversionStore store;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    DefineConversionHandler(
            SkuAccess skus, ConversionStore store, JdbcTemplate jdbc, AuditFacade audit, EventPublisher events) {
        this.skus = skus;
        this.store = store;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(DefineConversion command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        SkuIdentity sku = skus.requireOwned(command.skuId(), scope);

        String uom = requiredUnit(command.uomCode());
        BigDecimal factor = command.factorToBase();
        LocalDate from = command.effectiveFrom();
        LocalDate to = command.effectiveTo();

        if (from == null) {
            throw new ProblemException("request.field.required", Map.of("field", "effectiveFrom"));
        }
        if (factor == null || factor.signum() <= 0) {
            throw new ProblemException("m2.conversion.factor_not_positive");
        }
        if (to != null && to.isBefore(from)) {
            throw new ProblemException("m2.conversion.effective_range_invalid");
        }

        boolean unitIsWeight = store.unitIsWeight(uom)
                .orElseThrow(() -> new ProblemException("m2.conversion.uom_unknown", Map.of("uomCode", uom)));

        // The base unit converts to itself with factor one; a row for it would only collide.
        if (uom.equals(sku.baseUomCode())) {
            throw new ProblemException("m2.conversion.base_unit", Map.of("uomCode", uom));
        }

        // 22A section 6: "sku not sold_by_weight for CASE-type units". A weighed item (base KG)
        // has no case, packet or dozen: a retail pack of it is another SKU made by an M5 recipe
        // (doc 22 section 3.2, ADR-07).
        if (sku.soldByWeight() && !unitIsWeight) {
            throw new ProblemException("m2.conversion.weight_sku_count_unit", Map.of("uomCode", uom));
        }

        // The row in force before this one, when there is one that starts earlier: it ends the
        // day before the new row starts. A row that starts on or after the new date is an
        // overlap the exclusion constraint refuses below.
        Optional<ConversionRow> open = store.findOpenRow(sku.skuId(), uom)
                .filter(row -> row.effectiveFrom().isBefore(from));

        ConversionRow closed = null;

        try {
            if (open.isPresent()) {
                jdbc.update(
                        """
                        update catalogue.sku_uom_conversion
                        set effective_to = ?
                        where sku_id = ? and uom_code = ? and effective_to is null
                        """,
                        from.minusDays(1),
                        sku.skuId(),
                        uom);
                ConversionRow before = open.get();
                closed = new ConversionRow(
                        before.skuId(),
                        before.uomCode(),
                        before.factorToBase(),
                        before.effectiveFrom(),
                        from.minusDays(1),
                        before.ownerEntityId());
            }

            jdbc.update(
                    """
                    insert into catalogue.sku_uom_conversion
                        (sku_id, uom_code, factor_to_base, effective_from, effective_to, owner_entity_id)
                    values (?, ?, ?, ?, ?, ?)
                    """,
                    sku.skuId(),
                    uom,
                    factor,
                    from,
                    to,
                    sku.ownerEntityId());
        } catch (DataIntegrityViolationException e) {
            if (isExclusionViolation(e)) {
                throw new ProblemException("m2.conversion.overlap", Map.of("uomCode", uom));
            }
            throw e;
        }

        ConversionRow defined = new ConversionRow(sku.skuId(), uom, factor, from, to, sku.ownerEntityId());

        audit.record(
                AUDIT_DEFINED,
                Subject.of("sku", sku.skuId()),
                open.map(ConversionRow::auditState).orElse(null),
                closed == null
                        ? defined.auditState()
                        : Map.of("closed", closed.auditState(), "defined", defined.auditState()),
                scope);

        events.publish(new ConversionDefined(sku.skuId(), sku.ownerEntityId(), uom, factor, from, to));

        return sku.skuId();
    }

    private static String requiredUnit(String uomCode) {
        if (uomCode == null || uomCode.isBlank()) {
            throw new ProblemException("request.field.required", Map.of("field", "uomCode"));
        }
        return uomCode.strip().toUpperCase();
    }

    private static boolean isExclusionViolation(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof SQLException sql && EXCLUSION_VIOLATION.equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }
}
