package lk.coopfed.knoweb.m2catalogue.internal.sku;

import java.sql.Array;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.api.CreateSku;
import lk.coopfed.knoweb.m2catalogue.api.DuplicateSuspected;
import lk.coopfed.knoweb.m2catalogue.api.SkuCreated;
import lk.coopfed.knoweb.m2catalogue.internal.steward.DuplicateScorer.SuspectMatch;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@CommandHandler(permission = "cat.sku.create_local")
public class CreateLocalSkuHandler implements Handles<CreateSku, UUID> {

    private static final String AUDIT_CREATED = "SKU_CREATED";
    private static final int MAX_CODE_ATTEMPTS = 50;

    private final SkuRepository repository;
    private final SkuRules rules;
    private final SkuCodeGenerator codes;
    private final SkuDuplicateDetector duplicateDetector;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    CreateLocalSkuHandler(
            SkuRepository repository,
            SkuRules rules,
            SkuCodeGenerator codes,
            SkuDuplicateDetector duplicateDetector,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.repository = repository;
        this.rules = rules;
        this.codes = codes;
        this.duplicateDetector = duplicateDetector;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CreateSku command, ScopeContext scope) {
        SkuGuards.requireEntityWideScope(scope);

        if (command == null) {
            throw new ProblemException("request.invalid");
        }

        rules.validate(command.details());

        String code = nextAvailableCode();

        Sku sku = Sku.create(Ids.next(), code, scope.entityId(), command.details());

        repository.saveAndFlush(sku);

        audit.record(AUDIT_CREATED, Subject.of("sku", sku.getId()), null, sku.auditState(), scope);

        events.publish(new SkuCreated(sku.getId(), sku.ownerEntityId(), sku.skuCode(), sku.status()));

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

    private String nextAvailableCode() {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String candidate = codes.next();
            if (!repository.existsBySkuCode(candidate)) {
                return candidate;
            }
        }

        throw new ProblemException("m2.sku.code_generation_failed");
    }
}
