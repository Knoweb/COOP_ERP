package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistration;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBatch;
import lk.coopfed.knoweb.m5inventory.api.CountersignOpeningBalance;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalancePosted;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CountersignOpeningBalance (doc 25 section 4.7, SIGNED_ENTITY to POSTED: "Federation onboarding
 * officer; MFA; issuance (ENTITY series); OPENING_BALANCE movements"; flow 6.9).
 *
 * <p>Guards, in order: a Federation officer in the Federation's entity-wide OWN scope
 * ({@code m5.opening.federation_required}); the balance visible to that administration scope
 * ({@code m5.opening.not_found}); signed by the entity ({@code m5.opening.not_signed}); a
 * countersigner who is not the signer ({@code m5.opening.countersigner_is_signer}: two people, two
 * signatures); still no stock moved at the location ({@code m5.opening.location_has_stock}).
 *
 * <p>Mutation: the entity's OPB series registered where missing (no module registers ENTITY series
 * yet); the OPB document issued with one line per counted line (quantity, batch, cost at issue,
 * value); the OPENING_BALANCE movements posted citing it, which create the lots and seed the
 * entity average; the balance POSTED with its document. Audit {@code OPB_POSTED}; event
 * {@code opening_balance.posted.v1} (and the kernel's {@code document.issued.v1}, the ledger's
 * {@code stock.moved.v1}).
 *
 * <p>The command remains authorized and attributed to the Federation officer. After locking and
 * validating the MPCS row through its narrow federation-admin policy, its batch, document and
 * stock mutations run in the balance owner's scope so every resulting row retains the MPCS as
 * owner. The derived scope preserves the officer, MFA instant and correlation id.
 */
@Service
@CommandHandler(permission = "inv.opening.countersign", requiresMfa = true)
class CountersignOpeningBalanceHandler implements Handles<CountersignOpeningBalance, UUID> {

    static final String AUDIT_POSTED = "OPB_POSTED";

    private final OpeningBalanceStore store;
    private final PartyQueries party;
    private final BatchRegistration registration;
    private final NumberingService numbering;
    private final DocumentIssuance documents;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;

    CountersignOpeningBalanceHandler(
            OpeningBalanceStore store,
            PartyQueries party,
            BatchRegistration registration,
            NumberingService numbering,
            DocumentIssuance documents,
            StockLedger ledger,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.store = store;
        this.party = party;
        this.registration = registration;
        this.numbering = numbering;
        this.documents = documents;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public UUID handle(CountersignOpeningBalance command, ScopeContext scope) {
        OpeningBalanceGuards.requireFederation(scope, jdbc);
        OpeningBalanceStore.Header balance = store.lock(command.openingBalanceId())
                .orElseThrow(() -> new ProblemException(
                        "m5.opening.not_found",
                        Map.of("openingBalanceId", String.valueOf(command.openingBalanceId()))));
        if (!"SIGNED_ENTITY".equals(balance.status())) {
            throw new ProblemException("m5.opening.not_signed");
        }
        if (scope.userId().equals(balance.signedEntityBy())) {
            throw new ProblemException("m5.opening.countersigner_is_signer");
        }
        ScopeContext ownerScope = ownerScope(scope, balance.ownerEntityId());
        String entityCode = party.getEntity(balance.ownerEntityId(), ownerScope)
                .map(EntityView::entityCode)
                .orElseThrow(() -> new ProblemException("m5.opening.entity_unknown"));
        if (store.locationHasMovements(balance.locationId())) {
            throw new ProblemException("m5.opening.location_has_stock", Map.of("locationId", balance.locationId()));
        }
        List<OpeningBalanceStore.Line> lines = store.lines(balance.openingBalanceId());

        // The entity signed these retained attributes. Registration is deliberately postponed
        // until this posting transaction, so an abandoned draft creates no permanent batch.
        String originNo = "OPB-" + balance.openingBalanceId().toString().substring(0, 8);
        for (OpeningBalanceStore.Line line : lines) {
            if (line.batchId() == null) {
                UUID registered = registration
                        .register(
                                new RegisterBatch(
                                        line.skuId(),
                                        null,
                                        line.batchNo(),
                                        null,
                                        line.expiryDate(),
                                        line.printedMrp(),
                                        balance.openingBalanceId(),
                                        originNo,
                                        line.lineNo()),
                                ownerScope)
                        .batchId();
                jdbc.update(
                        "update inventory.opening_balance_line set batch_id = ? where line_id = ? and batch_id is null",
                        registered,
                        line.lineId());
            }
        }
        lines = store.lines(balance.openingBalanceId());

        // 1. the OPB document, from the entity's series
        numbering.registerSeries(
                SeriesRegistration.forEntity(OpeningBalanceDocumentType.OPB, balance.ownerEntityId(), entityCode),
                ownerScope);
        UUID documentId = Ids.next();
        List<DocumentLineRecord> documentLines = new ArrayList<>();
        for (OpeningBalanceStore.Line line : lines) {
            documentLines.add(new DocumentLineRecord(
                    line.lineId(),
                    documentId,
                    line.lineNo(),
                    line.skuId(),
                    line.batchId(),
                    null,
                    line.qty(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    line.qty().multiply(line.unitCost()).setScale(2, RoundingMode.HALF_UP),
                    line.unitCost(),
                    null,
                    null));
        }
        DocumentRecord issued = documents.issue(draft(documentId, balance, ownerScope), documentLines, ownerScope);

        // 2. the stock
        Instant now = clock.instant();
        List<Movement> movements = lines.stream()
                .map(line -> new Movement(
                        balance.locationId(),
                        line.batchId(),
                        LotCondition.valueOf(line.condition()),
                        MovementType.OPENING_BALANCE,
                        line.qty(),
                        line.unitCost(),
                        line.lineId()))
                .toList();
        ledger.post(new PostMovements(issued.id(), now, null, movements), ownerScope);

        jdbc.update(
                """
                update inventory.opening_balance
                   set status = 'POSTED', countersigned_by = ?, countersigned_at = ?, document_id = ?
                 where opening_balance_id = ?
                """,
                scope.userId(),
                Timestamp.from(now),
                issued.id(),
                balance.openingBalanceId());

        audit.record(
                AUDIT_POSTED,
                Subject.of("opening_balance", balance.openingBalanceId()),
                Map.of("status", "SIGNED_ENTITY"),
                Map.of(
                        "status", "POSTED",
                        "countersignedBy", scope.userId(),
                        "documentId", issued.id(),
                        "document", String.valueOf(issued.docNumberDisplay())),
                ownerScope);
        events.publish(new OpeningBalancePosted(
                balance.openingBalanceId(), balance.ownerEntityId(), balance.locationId(), issued.id(), lines.size()));
        return issued.id();
    }

    private static ScopeContext ownerScope(ScopeContext federationScope, UUID ownerEntityId) {
        Scope owner = new Scope(ownerEntityId, null);
        return new ScopeContext(
                federationScope.userId(),
                federationScope.deviceId(),
                federationScope.homeEntityId(),
                List.of(owner),
                owner,
                federationScope.policyClass(),
                federationScope.grantedEntities(),
                federationScope.mfaAt(),
                federationScope.locale(),
                federationScope.correlationId());
    }

    private static DocumentRecord draft(UUID documentId, OpeningBalanceStore.Header balance, ScopeContext scope) {
        return new DocumentRecord(
                documentId,
                OpeningBalanceDocumentType.OPB,
                null,
                null,
                null,
                balance.ownerEntityId(),
                null,
                balance.locationId(),
                null,
                null,
                "DRAFT",
                null,
                null,
                null,
                scope.userId(),
                "LKR",
                null,
                null,
                null,
                null,
                null,
                DocumentOrigin.ONLINE,
                null,
                null);
    }
}
