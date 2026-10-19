package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.sql.Array;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspected;
import lk.coopfed.knoweb.m2catalogue.api.InventoryLotQuery;
import lk.coopfed.knoweb.m2catalogue.api.SkuDetails;
import lk.coopfed.knoweb.m2catalogue.api.SkuUpdated;
import lk.coopfed.knoweb.m2catalogue.api.UpdateSku;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.create_local")
public class UpdateLocalSkuHandler implements Handles<UpdateSku, UUID> {

    private static final String AUDIT_UPDATED = "SKU_UPDATED";

    private final SkuRepository repository;
    private final SkuRules rules;
    private final InventoryLotQuery inventory;
    private final SkuDuplicateDetector duplicateDetector;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    UpdateLocalSkuHandler(
            SkuRepository repository,
            SkuRules rules,
            InventoryLotQuery inventory,
            SkuDuplicateDetector duplicateDetector,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.rules = rules;
        this.inventory = inventory;
        this.duplicateDetector = duplicateDetector;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(UpdateSku command, ScopeContext scope) {
        return update(command, scope);
    }

    private UUID update(UpdateSku command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        SkuDetails details = command.details();
        rules.validate(details);

        Sku sku = SkuGuards.requireOwned(repository, command.skuId(), scope);

        // A SHARED SKU is edited on the shared path only (UpdateSharedSkuHandler and its
        // Federation guard); the router sends it there, and this holds for any other caller.
        if (Sku.SHARED.equals(sku.status())) {
            throw new ProblemException(FederationCaller.FEDERATION_ONLY);
        }

        validateSharedTranslations(sku, details);
        validateInventoryIdentityChange(sku, details);

        Map<String, Object> before = sku.auditState();

        sku.update(details);
        repository.saveAndFlush(sku);

        audit.record(AUDIT_UPDATED, Subject.of("sku", sku.getId()), before, sku.auditState(), scope);

        events.publish(new SkuUpdated(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.status()));

        recordDuplicateSuspects(sku, scope);

        return sku.getId();
    }

    private void recordDuplicateSuspects(Sku sku, ScopeContext scope) {
        List<SuspectMatch> suspects = duplicateDetector.findDuplicates(sku, scope);
        for (SuspectMatch m : suspects) {
            UUID candId = sku.getId();
            UUID existId = m.matched().skuId();
            UUID a = candId.toString().compareTo(existId.toString()) < 0 ? candId : existId;
            UUID b = candId.toString().compareTo(existId.toString()) < 0 ? existId : candId;

            UUID suspectId = Ids.next();
            jdbc.update(
                    """
                    INSERT INTO catalogue.duplicate_suspect (
                        suspect_id, sku_a, sku_b, score, reasons, status, owner_entity_id
                    ) VALUES (?, ?, ?, ?, ?, 'OPEN', ?)
                    ON CONFLICT (sku_a, sku_b) DO NOTHING
                    """,
                    ps -> {
                        ps.setObject(1, suspectId);
                        ps.setObject(2, a);
                        ps.setObject(3, b);
                        ps.setBigDecimal(4, m.score());
                        Array reasonsArray = ps.getConnection()
                                .createArrayOf("text", m.reasons().toArray(new String[0]));
                        ps.setArray(5, reasonsArray);
                        ps.setObject(6, scope.entityId());
                    });

            events.publish(new DuplicateSuspected(suspectId, a, b, m.score(), m.reasons(), scope.entityId()));
        }
    }

    private void validateInventoryIdentityChange(Sku sku, SkuDetails details) {

        boolean changed =
                !sku.baseUomCode().equalsIgnoreCase(details.baseUomCode().strip())
                        || sku.batchTracked() != details.batchTracked()
                        || sku.expiryTracked() != details.expiryTracked();

        if (changed && inventory.hasAnyLot(sku.getId())) {
            throw new ProblemException("m2.sku.has_lots", Map.of("skuId", sku.getId()));
        }
    }

    private static void validateSharedTranslations(Sku sku, SkuDetails details) {

        if (Sku.SHARED.equals(sku.status()) && (blank(details.shortNameSi()) || blank(details.shortNameTa()))) {
            throw new ProblemException("m2.sku.shared_translations_required");
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
