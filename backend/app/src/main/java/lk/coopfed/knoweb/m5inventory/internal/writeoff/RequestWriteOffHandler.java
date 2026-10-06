package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.RequestWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WriteOffRequested;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RequestWriteOff (25A section 6.3: "location scope; lot exists; qty ≤ on hand; category → DRAFT";
 * doc 25 flow 6.4): the person who found the loss drafts the write-off at the location, a shop's
 * own staff included (inv.writeoff.request is a LOCATION permission).
 *
 * <p>Guards, in order: an OWN scope; the location one of the entity's that the scope reads
 * ({@code m5.location.not_in_scope}); a category ({@code m5.writeoff.category_required}); at least
 * one line ({@code m5.writeoff.lines_required}); each a batch, a condition and a quantity above
 * zero with three decimals at most, no lot twice ({@code m5.writeoff.line_invalid}); the batch
 * known to M2 ({@code m5.batch.not_found}); the lot holding the quantity
 * ({@code m5.writeoff.insufficient_stock}: what is not there cannot be lost; a negative lot is
 * corrected by a count).
 *
 * <p>Mutation: the WOF document as a kernel DRAFT with its lines (so photographs can be attached to
 * it before it is numbered), the write-off and its lines at the location, DRAFT. Audit
 * {@code WRITEOFF_REQUESTED}; event {@code writeoff.requested.v1}. No stock moves until approval.
 */
@Service
@CommandHandler(permission = "inv.writeoff.request")
class RequestWriteOffHandler implements Handles<RequestWriteOff, UUID> {

    static final String AUDIT_REQUESTED = "WRITEOFF_REQUESTED";

    private final WriteOffStore store;
    private final ControlPolicy policy;
    private final BatchQueries batches;
    private final DocumentBaseRepository documents;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one command, each used once
    RequestWriteOffHandler(
            WriteOffStore store,
            ControlPolicy policy,
            BatchQueries batches,
            DocumentBaseRepository documents,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.batches = batches;
        this.documents = documents;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RequestWriteOff command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        policy.requireEntityLocation(command.locationId(), scope);
        if (command.category() == null) {
            throw new ProblemException("m5.writeoff.category_required");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ProblemException("m5.writeoff.lines_required");
        }
        Set<String> seen = new HashSet<>();
        for (RequestWriteOff.Line line : command.lines()) {
            boolean valid = line != null
                    && line.batchId() != null
                    && line.condition() != null
                    && line.qty() != null
                    && line.qty().signum() > 0
                    && line.qty().stripTrailingZeros().scale() <= 3
                    && seen.add(line.batchId() + "/" + line.condition());
            if (!valid) {
                throw new ProblemException("m5.writeoff.line_invalid");
            }
        }
        List<UUID> skus = new ArrayList<>();
        for (RequestWriteOff.Line line : command.lines()) {
            BatchView batch = batches.getBatch(line.batchId(), scope)
                    .orElseThrow(() -> new ProblemException("m5.batch.not_found", Map.of("batchId", line.batchId())));
            skus.add(batch.skuId());
            BigDecimal onHand = store.onHand(
                    command.locationId(), line.batchId(), line.condition().name());
            if (onHand.compareTo(line.qty()) < 0) {
                throw new ProblemException("m5.writeoff.insufficient_stock", Map.of("batchId", line.batchId()));
            }
        }

        UUID id = Ids.next();
        String note = command.note() == null || command.note().isBlank()
                ? null
                : command.note().strip();
        documents.save(new DocumentRecord(
                id,
                WriteOffDocumentType.WOF,
                null,
                null,
                null,
                scope.entityId(),
                null,
                command.locationId(),
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
                note));
        jdbc.update(
                """
                insert into inventory.write_off
                    (write_off_id, owner_entity_id, location_id, category, note, requested_by, requested_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                scope.entityId(),
                command.locationId(),
                command.category().name(),
                note,
                scope.userId(),
                Timestamp.from(clock.instant()));
        List<DocumentLineRecord> documentLines = new ArrayList<>();
        for (int i = 0; i < command.lines().size(); i++) {
            RequestWriteOff.Line line = command.lines().get(i);
            UUID lineId = Ids.next();
            jdbc.update(
                    """
                    insert into inventory.write_off_line
                        (line_id, write_off_id, owner_entity_id, location_id, line_no, batch_id, sku_id, condition, qty)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    lineId,
                    id,
                    scope.entityId(),
                    command.locationId(),
                    i + 1,
                    line.batchId(),
                    skus.get(i),
                    line.condition().name(),
                    line.qty());
            documentLines.add(new DocumentLineRecord(
                    lineId,
                    id,
                    i + 1,
                    skus.get(i),
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
                    null,
                    null,
                    command.category().name(),
                    null));
        }
        documents.saveLines(id, documentLines);

        audit.record(
                AUDIT_REQUESTED,
                Subject.of("write_off", id),
                null,
                Map.of(
                        "locationId", command.locationId(),
                        "category", command.category().name(),
                        "lines", command.lines().size(),
                        "status", "DRAFT"),
                scope);
        events.publish(new WriteOffRequested(
                id,
                scope.entityId(),
                command.locationId(),
                command.category().name(),
                command.lines().size()));
        return id;
    }
}
