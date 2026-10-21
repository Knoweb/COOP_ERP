package lk.coopfed.knoweb.m2catalogue.internal.tax;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.PublishTaxRate;
import lk.coopfed.knoweb.m2catalogue.api.TaxRatePublished;
import lk.coopfed.knoweb.m2catalogue.internal.sku.FederationCaller;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PublishTaxRate (22A section 6; doc 22 section 3.6): publishes an effective-dated tax rate.
 * Closes the active rate in force at apply_from - 1, and inserts the new rate.
 * Only the Federation may publish tax rates (cat.tax.publish, MFA required).
 */
@Service
@CommandHandler(permission = "cat.tax.publish", requiresMfa = true)
public class PublishTaxRateHandler implements Handles<PublishTaxRate, Void> {

    static final String AUDIT_PUBLISHED = "TAX_RATE_PUBLISHED";

    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final FederationCaller federation;
    private final Clock clock;

    public PublishTaxRateHandler(
            JdbcTemplate jdbc, AuditFacade audit, EventPublisher events, FederationCaller federation, Clock clock) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.federation = federation;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Void handle(PublishTaxRate command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        federation.require(scope);

        if (command.taxCategoryId() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "taxCategoryId"));
        }
        if (command.ratePercent() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "ratePercent"));
        }
        if (command.applyFrom() == null) {
            throw new ProblemException("request.field.required", Map.of("field", "applyFrom"));
        }

        UUID taxCategoryId = command.taxCategoryId();
        Integer exists = jdbc.queryForObject(
                "select count(*) from catalogue.tax_category where tax_category_id = ?", Integer.class, taxCategoryId);
        if (exists == null || exists == 0) {
            throw new ProblemException("m2.tax.category_not_found", Map.of("taxCategoryId", taxCategoryId));
        }

        BigDecimal ratePercent = command.ratePercent();
        if (ratePercent.compareTo(BigDecimal.ZERO) < 0 || ratePercent.compareTo(new BigDecimal("100")) > 0) {
            throw new ProblemException("m2.tax.rate_invalid", Map.of("ratePercent", ratePercent));
        }

        LocalDate today = LocalDate.now(clock);
        if (command.applyFrom().isBefore(today)) {
            throw new ProblemException(
                    "m2.tax.apply_from_in_past", Map.of("applyFrom", command.applyFrom(), "today", today));
        }

        Integer conflict = jdbc.queryForObject(
                "select count(*) from catalogue.tax_rate where tax_category_id = ? and effective_from >= ?",
                Integer.class,
                taxCategoryId,
                command.applyFrom());
        if (conflict != null && conflict > 0) {
            throw new ProblemException("m2.tax.rate_conflict", Map.of("applyFrom", command.applyFrom()));
        }

        LocalDate closeDate = command.applyFrom().minusDays(1);
        jdbc.update(
                """
                update catalogue.tax_rate
                set effective_to = ?
                where tax_category_id = ?
                  and effective_from <= ?
                  and (effective_to is null or effective_to > ?)
                """,
                closeDate,
                taxCategoryId,
                closeDate,
                closeDate);

        jdbc.update(
                """
                insert into catalogue.tax_rate
                    (tax_category_id, rate_percent, effective_from, effective_to, owner_entity_id)
                values (?, ?, ?, null, ?)
                """,
                taxCategoryId,
                ratePercent,
                command.applyFrom(),
                scope.entityId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("taxCategoryId", taxCategoryId);
        after.put("ratePercent", ratePercent);
        after.put("applyFrom", command.applyFrom());
        after.put("ownerEntityId", scope.entityId());

        audit.record(
                AUDIT_PUBLISHED,
                Subject.of("tax_category", taxCategoryId),
                null,
                Collections.unmodifiableMap(after),
                scope);

        events.publish(new TaxRatePublished(taxCategoryId, ratePercent, command.applyFrom()));

        return null;
    }
}
