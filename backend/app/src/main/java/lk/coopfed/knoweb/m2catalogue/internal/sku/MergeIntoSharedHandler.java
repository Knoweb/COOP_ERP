package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.MergeIntoShared;
import lk.coopfed.knoweb.m2catalogue.api.SkuMerged;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles merging a LOCAL SKU into an existing SHARED SKU (22A section 6; doc 22 section 6.3).
 */
@Service
@CommandHandler(permission = "cat.sku.promote")
public class MergeIntoSharedHandler implements Handles<MergeIntoShared, UUID> {

    public static final String AUDIT_MERGED = "SKU_MERGED";

    private final SkuRepository repository;
    private final FederationCaller federation;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    public MergeIntoSharedHandler(
            SkuRepository repository,
            FederationCaller federation,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.repository = repository;
        this.federation = federation;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(MergeIntoShared command, ScopeContext scope) {
        federation.require(scope);

        if (command == null || command.sourceSkuId() == null || command.targetSkuId() == null) {
            throw new ProblemException("request.invalid");
        }

        if (command.sourceSkuId().equals(command.targetSkuId())) {
            throw new ProblemException("m2.sku.cannot_merge_with_self");
        }

        // Under OWN scope, another entity's LOCAL SKU and barcodes are hidden by RLS.
        // Switch scope_class to FEDERATION_VIEW to read source and query its active barcodes.
        Sku source;
        List<Map<String, Object>> barcodes;
        jdbc.execute("SELECT set_config('app.scope_class', 'FEDERATION_VIEW', true)");
        try {
            source = repository.findById(command.sourceSkuId()).orElse(null);
            barcodes = source == null
                    ? List.of()
                    : jdbc.queryForList(
                            """
                            SELECT barcode, symbology, owner_entity_id
                              FROM catalogue.sku_barcode
                             WHERE sku_id = ? AND status = 'ACTIVE'
                            """,
                            command.sourceSkuId());

            if (source == null) {
                throw new ProblemException("m2.sku.not_found", Map.of("skuId", command.sourceSkuId()));
            }

            if (!Sku.LOCAL.equals(source.status())) {
                throw new ProblemException("m2.sku.not_local", Map.of("skuId", source.getId()));
            }
        } finally {
            jdbc.queryForList(
                    "SELECT set_config('app.scope_class', ?, true)",
                    scope.policyClass().name());
        }

        Sku target = repository
                .findById(command.targetSkuId())
                .orElseThrow(() -> new ProblemException("m2.sku.not_found", Map.of("skuId", command.targetSkuId())));

        if (!Sku.SHARED.equals(target.status())) {
            throw new ProblemException("m2.sku.target_not_shared", Map.of("skuId", target.getId()));
        }

        if (!source.baseUomCode().equalsIgnoreCase(target.baseUomCode())) {
            throw new ProblemException("m2.sku.base_uom_mismatch");
        }

        if (source.soldByWeight() != target.soldByWeight()
                || source.batchTracked() != target.batchTracked()
                || source.expiryTracked() != target.expiryTracked()) {
            throw new ProblemException("m2.sku.unit_conflict");
        }

        UUID sourceOwner = source.ownerEntityId();
        Map<String, Object> sourceBefore = source.auditState();

        LocalDate effectiveDate = LocalDate.now(clock);
        // Under app.fed_promote, insert alias, re-point existing aliases, move active barcodes and retire source SKU
        jdbc.execute("SELECT set_config('app.fed_promote', 'true', true)");
        try {
            jdbc.update(
                    """
                    INSERT INTO catalogue.sku_alias (alias_sku_id, canonical_sku_id, effective_from, owner_entity_id)
                    VALUES (?, ?, ?, ?)
                    ON CONFLICT (alias_sku_id) DO UPDATE SET canonical_sku_id = EXCLUDED.canonical_sku_id
                    """,
                    source.getId(),
                    target.getId(),
                    Date.valueOf(effectiveDate),
                    sourceOwner);

            jdbc.update(
                    """
                    UPDATE catalogue.sku_alias
                       SET canonical_sku_id = ?
                     WHERE canonical_sku_id = ?
                    """,
                    target.getId(),
                    source.getId());

            for (Map<String, Object> row : barcodes) {
                String barcode = (String) row.get("barcode");
                String symbology = (String) row.get("symbology");
                UUID barcodeOwner = (UUID) row.get("owner_entity_id");

                Integer collisionCount = jdbc.queryForObject(
                        """
                        SELECT count(*)
                          FROM catalogue.sku_barcode
                         WHERE barcode = ? AND symbology = ? AND sku_id = ? AND status = 'ACTIVE'
                        """,
                        Integer.class,
                        barcode,
                        symbology,
                        target.getId());

                if (collisionCount != null && collisionCount > 0) {
                    // Colliding with active barcode on target: retire source barcode
                    jdbc.update(
                            """
                            UPDATE catalogue.sku_barcode
                               SET status = 'RETIRED'
                             WHERE barcode = ? AND symbology = ? AND owner_entity_id = ?
                            """,
                            barcode,
                            symbology,
                            barcodeOwner);
                } else {
                    // Move barcode to target SKU
                    jdbc.update(
                            """
                            UPDATE catalogue.sku_barcode
                               SET sku_id = ?
                             WHERE barcode = ? AND symbology = ? AND owner_entity_id = ?
                            """,
                            target.getId(),
                            barcode,
                            symbology,
                            barcodeOwner);
                }
            }

            // Source SKU becomes INACTIVE with prior_status LOCAL
            source.mergeIntoShared();
            repository.saveAndFlush(source);
        } finally {
            jdbc.execute("SELECT set_config('app.fed_promote', '', true)");
        }
        Map<String, Object> sourceAfter = source.auditState();

        // Close any open duplicate suspect pair
        Instant now = clock.instant();
        jdbc.update(
                """
                UPDATE catalogue.duplicate_suspect
                   SET status = 'MERGED', decided_by = ?, decided_at = ?
                 WHERE ((sku_a = ? AND sku_b = ?) OR (sku_a = ? AND sku_b = ?))
                   AND status = 'OPEN'
                """,
                scope.userId(),
                Timestamp.from(now),
                source.getId(),
                target.getId(),
                target.getId(),
                source.getId());

        audit.record(AUDIT_MERGED, Subject.of("sku", source.getId()), sourceBefore, sourceAfter, scope);

        events.publish(new SkuMerged(
                source.getId(), target.getId(), sourceOwner, source.skuCode(), target.skuCode(), source.status()));

        return target.getId();
    }
}
