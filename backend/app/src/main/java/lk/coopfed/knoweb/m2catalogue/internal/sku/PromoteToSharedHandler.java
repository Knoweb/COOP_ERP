package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.PromoteToShared;
import lk.coopfed.knoweb.m2catalogue.api.SkuPromoted;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles promoting a LOCAL SKU to SHARED (22A section 6; doc 22 section 6.3).
 */
@Service
@CommandHandler(permission = "cat.sku.promote")
public class PromoteToSharedHandler implements Handles<PromoteToShared, UUID> {

    public static final String AUDIT_PROMOTED = "SKU_PROMOTED";

    private final SkuRepository repository;
    private final SkuReferenceData references;
    private final FederationCaller federation;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public PromoteToSharedHandler(
            SkuRepository repository,
            SkuReferenceData references,
            FederationCaller federation,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.repository = repository;
        this.references = references;
        this.federation = federation;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(PromoteToShared command, ScopeContext scope) {
        federation.require(scope);

        if (command == null || command.skuId() == null) {
            throw new ProblemException("request.invalid");
        }

        // Under OWN scope, another entity's LOCAL SKU is hidden by RLS.
        // Temporarily switch scope_class to FEDERATION_VIEW to read the local SKU, then restore to OWN.
        jdbc.execute("SELECT set_config('app.scope_class', 'FEDERATION_VIEW', true)");
        Sku sku = repository.findById(command.skuId()).orElse(null);
        jdbc.execute("SELECT set_config('app.scope_class', 'OWN', true)");

        if (sku == null) {
            throw new ProblemException("m2.sku.not_found", Map.of("skuId", command.skuId()));
        }

        if (!Sku.LOCAL.equals(sku.status())) {
            throw new ProblemException("m2.sku.not_local", Map.of("skuId", sku.getId()));
        }

        if (sku.shortNameSi() == null
                || sku.shortNameTa() == null
                || sku.shortNameSi().isBlank()
                || sku.shortNameTa().isBlank()) {
            throw new ProblemException("m2.sku.shared_translations_required");
        }

        if (references.hasNonGovernedTags(sku.getId())) {
            throw new ProblemException("m2.sku.governed_tags_only", Map.of("skuId", sku.getId()));
        }

        if (references.hasCaseBarcodeWithoutConversion(sku.getId())) {
            throw new ProblemException("m2.sku.case_conversion_required", Map.of("skuId", sku.getId()));
        }

        UUID originOwner = sku.ownerEntityId();
        Map<String, Object> before = sku.auditState();

        sku.promoteToShared(scope.entityId());
        repository.saveAndFlush(sku);

        LocalDate effectiveDate = LocalDate.now(clock);
        jdbc.update(
                """
                INSERT INTO catalogue.sku_alias (alias_sku_id, canonical_sku_id, effective_from, owner_entity_id)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (alias_sku_id) DO UPDATE SET canonical_sku_id = EXCLUDED.canonical_sku_id
                """,
                sku.getId(),
                sku.getId(),
                Date.valueOf(effectiveDate),
                originOwner);

        audit.record(AUDIT_PROMOTED, Subject.of("sku", sku.getId()), before, sku.auditState(), scope);

        events.publish(new SkuPromoted(sku.getId(), originOwner, sku.skuCode(), sku.status()));

        return sku.getId();
    }
}
