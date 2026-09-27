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
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
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
 * <p>Guards, in order: an OWN user ({@code m5.opening.user_required}); the balance visible to the
 * scope ({@code m5.opening.not_found}); signed by the entity ({@code m5.opening.not_signed}); a
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
 * <p>Demo scope (README): the countersignature is given in the entity's own scope, by a user who
 * holds {@code inv.opening.countersign} there (the Federation's officer at the entity, or the
 * Federation for its own warehouses). A Federation officer countersigning from the Federation's
 * scope needs a Federation-administers policy on the balance and the posting in the owner's scope;
 * deferred.
 */
@Service
@CommandHandler(permission = "inv.opening.countersign", requiresMfa = true)
class CountersignOpeningBalanceHandler implements Handles<CountersignOpeningBalance, UUID> {

    static final String AUDIT_POSTED = "OPB_POSTED";

    private final OpeningBalanceStore store;
    private final PartyQueries party;
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
            NumberingService numbering,
            DocumentIssuance documents,
            StockLedger ledger,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events,
            Clock clock) {
        this.store = store;
        this.party = party;
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
        OpeningBalanceGuards.requireUser(scope);
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
        if (store.locationHasMovements(balance.locationId())) {
            throw new ProblemException("m5.opening.location_has_stock", Map.of("locationId", balance.locationId()));
        }
        List<OpeningBalanceStore.Line> lines = store.lines(balance.openingBalanceId());

        // 1. the OPB document, from the entity's series
        String entityCode = party.getEntity(balance.ownerEntityId(), scope)
                .map(EntityView::entityCode)
                .orElseThrow(() -> new ProblemException("m5.opening.entity_unknown"));
        numbering.registerSeries(
                SeriesRegistration.forEntity(OpeningBalanceDocumentType.OPB, balance.ownerEntityId(), entityCode),
                scope);
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
        DocumentRecord issued = documents.issue(draft(documentId, balance, scope), documentLines, scope);

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
        ledger.post(new PostMovements(issued.id(), now, null, movements), scope);

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
                scope);
        events.publish(new OpeningBalancePosted(
                balance.openingBalanceId(), balance.ownerEntityId(), balance.locationId(), issued.id(), lines.size()));
        return issued.id();
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
