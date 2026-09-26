package lk.coopfed.knoweb.kernel.internal.document;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.BusinessDate;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentIssued;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentOrigin;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentStateHistoryRecord;
import lk.coopfed.knoweb.kernel.api.DocumentType;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.DocumentTypes;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.SeriesScope;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The issuance protocol of doc 18 section 5.5, in the order 19A section 7 gives it. One
 * transaction, the caller's: a failure at any step, the type's validator included, rolls
 * back the number with everything else.
 */
@Component
class IssuanceProtocol implements DocumentIssuance {

    static final String STATUS_DRAFT = "DRAFT";
    static final String STATUS_ISSUED = "ISSUED";
    static final String AUDIT_ISSUED = "DOCUMENT_ISSUED";

    private final DocumentTypes types;
    private final Map<String, DocumentTypeHandler> handlers = new HashMap<>();
    private final JdbcNumberingService numbering;
    private final JdbcDocumentBaseRepository documents;
    private final BusinessDate businessDate;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final Clock clock;
    private final ZoneId businessZone;

    IssuanceProtocol(
            DocumentTypes types,
            List<DocumentTypeHandler> typeHandlers,
            JdbcNumberingService numbering,
            JdbcDocumentBaseRepository documents,
            BusinessDate businessDate,
            AuditFacade audit,
            EventPublisher events,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.types = types;
        this.numbering = numbering;
        this.documents = documents;
        this.businessDate = businessDate;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);

        for (DocumentTypeHandler handler : typeHandlers) {
            DocumentTypeHandler already = handlers.putIfAbsent(handler.docTypeCode(), handler);
            if (already != null) {
                throw new IllegalStateException("Two DocumentTypeHandler beans own the document type "
                        + handler.docTypeCode() + ": " + already.getClass().getName() + " and "
                        + handler.getClass().getName());
            }
        }
    }

    @Override
    public DocumentRecord issue(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "DocumentIssuance.issue was called outside a transaction; call it inside the handler's"
                            + " @Transactional method");
        }

        if (draft == null || draft.id() == null) {
            throw new ProblemException("document.not_draft");
        }

        // The stored header and lines are what is issued, locked so that nothing joins or
        // changes them while the number is taken: a module that keeps drafts may pass a copy
        // that is behind the store (updateDraft never writes the parties or the place), and the
        // hash must be over the rows that exist. The caller's record only says which document.
        Optional<DocumentRecord> stored = documents.findByIdForUpdate(draft.id());
        DocumentRecord header = stored.orElse(draft);

        // 1. the base's checks, then the type's validator: no invalid document takes a number.
        if (!STATUS_DRAFT.equals(header.status()) || header.isIssued()) {
            throw new ProblemException("document.not_draft");
        }

        DocumentType type = types.find(header.docTypeCode())
                .orElseThrow(() -> new ProblemException(
                        "document.type_unknown", Map.of("docTypeCode", String.valueOf(header.docTypeCode()))));

        DocumentTypeHandler handler = handlers.get(type.code());

        if (handler == null) {
            throw new ProblemException("document.type_unowned", Map.of("docTypeCode", type.code()));
        }

        if (ctx == null || ctx.entityId() == null || !ctx.entityId().equals(header.ownerEntityId())) {
            throw new ProblemException("document.owner_mismatch");
        }

        if (type.bilateral() && header.counterpartyEntityId() == null) {
            throw new ProblemException("document.counterparty_required", Map.of("docTypeCode", type.code()));
        }

        // 19A section 7: offline scopes are never issued here; a till numbers its own documents
        // and they arrive with the number through ingestion (K-08).
        if (header.origin() == DocumentOrigin.OFFLINE) {
            throw new ProblemException("document.series_device_held", Map.of("docTypeCode", type.code()));
        }

        List<DocumentLineRecord> storedLines = stored.isPresent() ? documents.findLines(header.id()) : List.of();
        List<DocumentLineRecord> documentLines =
                storedLines.isEmpty() ? (lines == null ? List.of() : List.copyOf(lines)) : storedLines;

        handler.validate(header, documentLines, ctx);

        // 2. the number, from the finest series that exists for this issuer at this place.
        Series series = numbering
                .seriesFor(type.code(), header.ownerEntityId(), header.locationId(), header.tillPositionId())
                .orElseThrow(() -> new ProblemException("document.series_missing", Map.of("docTypeCode", type.code())));

        // The counter of a TILL_POSITION series, or of any series a device holds, is the
        // device's (doc 18 section 5.5: "the holder device holds the authoritative counter"):
        // central taking a number from it would hand out the same number twice.
        if (series.holderDeviceId() != null || series.scope() == SeriesScope.TILL_POSITION) {
            throw new ProblemException(
                    "document.series_device_held", Map.of("docTypeCode", type.code(), "prefix", series.prefix()));
        }

        long number = numbering.takeNumber(series.seriesId());
        String display = String.format(JdbcNumberingService.NUMBER_FORMAT, series.prefix(), number);

        // 3. freeze totals, hash, timestamps; store the rows and the DRAFT -> ISSUED state row.
        // Microseconds: what timestamptz keeps, so the stored instant is exactly the hashed one.
        Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        LocalDateTime issuedLocal = LocalDateTime.ofInstant(issuedAt, businessZone);
        LocalDate date = businessDateFor(header, ctx, issuedAt);

        Totals totals = Totals.of(documentLines);

        DocumentRecord numbered = new DocumentRecord(
                header.id(),
                type.code(),
                series.seriesId(),
                number,
                display,
                header.ownerEntityId(),
                header.counterpartyEntityId(),
                header.locationId(),
                header.tillPositionId(),
                header.deviceId(),
                STATUS_ISSUED,
                issuedAt,
                issuedLocal,
                date,
                header.operatorUserId() != null ? header.operatorUserId() : ctx.userId(),
                header.currency() == null ? "LKR" : header.currency(),
                totals.net(),
                totals.tax(),
                totals.gross(),
                header.referenceDocumentId(),
                null,
                DocumentOrigin.ONLINE,
                header.deviceSeq(),
                header.notes());

        DocumentRecord issued = numbered.withContentHash(ContentHash.of(numbered, documentLines));

        // The rows in the order the database allows: the header as a draft, its lines, then the
        // one update that issues it. No line joins a header whose issued_at is set (V0055).
        if (stored.isEmpty()) {
            documents.save(header);
        }

        if (storedLines.isEmpty() && !documentLines.isEmpty()) {
            documents.saveLines(issued.id(), documentLines);
        }

        documents.writeIssued(issued);

        documents.insertHistory(new DocumentStateHistoryRecord(
                Ids.next(),
                issued.id(),
                STATUS_DRAFT,
                STATUS_ISSUED,
                issuedAt,
                issuedLocal,
                ctx.userId(),
                ctx.deviceId(),
                null,
                null));

        audit.record(
                AUDIT_ISSUED,
                Subject.of("document", issued.id()),
                Map.of("status", STATUS_DRAFT),
                Map.of(
                        "status", STATUS_ISSUED,
                        "docTypeCode", type.code(),
                        "docNumberDisplay", display,
                        "grossAmount", String.valueOf(totals.gross()),
                        "contentHash", issued.contentHash()),
                ctx);

        // 4. the owning module's post-issue hook, in the same transaction.
        handler.afterIssue(issued, documentLines, ctx);

        // 5. the base's event; the type-specific one is the module's.
        events.publish(new DocumentIssued(
                issued.id(), type.code(), display, issued.ownerEntityId(), issued.counterpartyEntityId()));

        return issued;
    }

    /**
     * The business date of the document (19A section 13: from the location's day-close state,
     * never the wall clock), read held so that a day close of that location waits for this
     * transaction. A document without a location (an entity-level order or discount) takes
     * the date of the location the issuer acts at; an issuer acting entity-wide has none, and
     * the document then takes the calendar date in the business time zone, a deviation from
     * 19A section 13 recorded in docs/PROGRESS.md until an entity has a business date of its own.
     */
    private LocalDate businessDateFor(DocumentRecord draft, ScopeContext ctx, Instant issuedAt) {
        if (draft.locationId() != null) {
            return businessDate.currentHeld(draft.locationId());
        }

        if (ctx.locationId() != null) {
            return businessDate.currentHeld(ctx.locationId());
        }

        return LocalDate.ofInstant(issuedAt, businessZone);
    }

    /** Totals frozen from the lines (money scale 2): net is the sum of line totals, gross is net plus tax. */
    record Totals(BigDecimal net, BigDecimal tax, BigDecimal gross) {

        static Totals of(List<DocumentLineRecord> lines) {
            BigDecimal net = BigDecimal.ZERO;
            BigDecimal tax = BigDecimal.ZERO;
            for (DocumentLineRecord line : lines) {
                net = net.add(orZero(line.lineTotal()));
                tax = tax.add(orZero(line.taxAmount()));
            }
            net = net.setScale(2, RoundingMode.HALF_UP);
            tax = tax.setScale(2, RoundingMode.HALF_UP);
            return new Totals(net, tax, net.add(tax));
        }

        private static BigDecimal orZero(BigDecimal value) {
            return value == null ? BigDecimal.ZERO : value;
        }
    }
}
