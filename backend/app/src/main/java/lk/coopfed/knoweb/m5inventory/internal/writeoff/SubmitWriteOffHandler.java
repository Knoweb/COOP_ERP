package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesRegistration;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m5inventory.api.SubmitWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WriteOffSubmitted;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * SubmitWriteOff (25A section 6.3: "DRAFT; photos referenced when category requires or location
 * single-staff; issuance (LOCATION or ENTITY series) → REQUESTED"; H-03: approval-path documents
 * issue online).
 *
 * <p>Guards, in order: an OWN scope; the write-off visible ({@code m5.writeoff.not_found}); DRAFT
 * ({@code m5.writeoff.not_draft}); a photograph when the category or the location needs one
 * ({@code m5.writeoff.photos_required}: THEFT and SHRINKAGE_UNEXPLAINED by default, and every
 * write-off of a single-staff location); each lot still holding its quantity
 * ({@code m5.writeoff.insufficient_stock}).
 *
 * <p>Mutation: the location's WOF series registered where missing, the WOF document issued from
 * it; the value at the entity average (doc 25 DR-3) and its band; REQUESTED. Audit
 * {@code WRITEOFF_SUBMITTED}; event {@code writeoff.submitted.v1} (and the kernel's
 * {@code document.issued.v1}).
 */
@Service
@CommandHandler(permission = "inv.writeoff.request")
class SubmitWriteOffHandler implements Handles<SubmitWriteOff, UUID> {

    static final String AUDIT_SUBMITTED = "WRITEOFF_SUBMITTED";

    private final WriteOffStore store;
    private final ControlPolicy policy;
    private final PartyQueries party;
    private final NumberingService numbering;
    private final DocumentBaseRepository documentBase;
    private final DocumentIssuance documents;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one command, each used once
    SubmitWriteOffHandler(
            WriteOffStore store,
            ControlPolicy policy,
            PartyQueries party,
            NumberingService numbering,
            DocumentBaseRepository documentBase,
            DocumentIssuance documents,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.party = party;
        this.numbering = numbering;
        this.documentBase = documentBase;
        this.documents = documents;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(SubmitWriteOff command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        WriteOffStore.Header writeOff = store.lock(command.writeOffId());
        if (!"DRAFT".equals(writeOff.status())) {
            throw new ProblemException("m5.writeoff.not_draft");
        }
        LocationView location = policy.requireEntityLocation(writeOff.locationId(), scope);
        if (policy.photosRequired(writeOff.category(), location, scope)
                && store.photos(writeOff.writeOffId()).isEmpty()) {
            throw new ProblemException("m5.writeoff.photos_required");
        }
        List<WriteOffStore.Line> lines = store.lines(writeOff.writeOffId());
        for (WriteOffStore.Line line : lines) {
            if (store.onHand(writeOff.locationId(), line.batchId(), line.condition())
                            .compareTo(line.qty())
                    < 0) {
                throw new ProblemException("m5.writeoff.insufficient_stock", Map.of("batchId", line.batchId()));
            }
        }

        String entityCode = party.getEntity(writeOff.ownerEntityId(), scope)
                .map(EntityView::entityCode)
                .orElseThrow(() -> new ProblemException("m5.writeoff.entity_unknown"));
        numbering.registerSeries(
                SeriesRegistration.forLocation(
                        WriteOffDocumentType.WOF,
                        writeOff.ownerEntityId(),
                        writeOff.locationId(),
                        entityCode,
                        location.locationCode(),
                        null),
                scope);
        DocumentRecord draft = documentBase
                .findById(writeOff.writeOffId())
                .orElseThrow(() -> new ProblemException("m5.writeoff.not_found"));
        DocumentRecord issued = documents.issue(draft, List.of(), scope);
        WriteOffStore.Valuation valuation = store.value(lines, writeOff.locationId(), scope);
        BigDecimal value = valuation.value();
        int band = policy.band(value, valuation.zeroCostLine(), scope);
        jdbc.update(
                """
                update inventory.write_off
                   set status = 'REQUESTED', submitted_at = ?, document_no = ?, value = ?, band = ?
                 where write_off_id = ?
                """,
                Timestamp.from(clock.instant()),
                issued.docNumberDisplay(),
                value,
                band,
                writeOff.writeOffId());

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "REQUESTED");
        after.put("document", String.valueOf(issued.docNumberDisplay()));
        after.put("value", value.toPlainString());
        after.put("band", band);
        audit.record(
                AUDIT_SUBMITTED,
                Subject.of("write_off", writeOff.writeOffId()),
                Map.of("status", "DRAFT"),
                after,
                scope);
        events.publish(new WriteOffSubmitted(
                writeOff.writeOffId(),
                writeOff.ownerEntityId(),
                writeOff.locationId(),
                issued.docNumberDisplay(),
                value,
                band));
        return writeOff.writeOffId();
    }
}
