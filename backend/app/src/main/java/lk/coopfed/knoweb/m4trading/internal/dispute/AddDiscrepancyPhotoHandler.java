package lk.coopfed.knoweb.m4trading.internal.dispute;

import java.sql.Timestamp;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.AddDiscrepancyPhoto;
import lk.coopfed.knoweb.m4trading.api.DiscrepancyPhotoAdded;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A photograph for a delivery discrepancy (ORD-10, doc 24 section 3.4; 19A section 9):
 * the kernel authorises one upload against the discrepancy document and records the attachment PENDING;
 * the client PUTs the bytes; the kernel's verifier makes it COMPLETE.
 *
 * <p>Guards, in order: the buyer's entity-wide OWN scope; the caller's own discrepancy ({@code
 * m4.discrepancy.not_found}); not settled ({@code m4.discrepancy.settled_already}: evidence is fixed once
 * settled); the kernel's own guards on type and size ({@code attachment.*}).
 *
 * <p>Mutation: the kernel's PENDING attachment and the discrepancy's photo row. Audit DISCREPANCY_PHOTO_ADDED
 * (the kernel records its own); event discrepancy.photo_added.v1.
 */
@Service
@CommandHandler(permission = "del.discrepancy.resolve")
public class AddDiscrepancyPhotoHandler implements Handles<AddDiscrepancyPhoto, Attachments.PresignedUpload> {

    static final String AUDIT_PHOTO = "DISCREPANCY_PHOTO_ADDED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final Attachments attachments;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    AddDiscrepancyPhotoHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            Attachments attachments,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.attachments = attachments;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Attachments.PresignedUpload handle(AddDiscrepancyPhoto command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID discrepancyId = TradingGuards.required(command.discrepancyId(), "discrepancyId");

        DocumentRecord header = documents
                .findById(discrepancyId)
                .filter(doc -> GrnReads.DISC.equals(doc.docTypeCode()))
                .filter(doc -> scope.entityId().equals(doc.ownerEntityId()))
                .orElseThrow(() -> new ProblemException("m4.discrepancy.not_found"));

        boolean existsInTrading = !jdbc.queryForList(
                        "select 1 from trading.doc_discrepancy where document_id = ?", Integer.class, discrepancyId)
                .isEmpty();
        if (!existsInTrading) {
            throw new ProblemException("m4.discrepancy.not_found");
        }

        boolean settled = !jdbc.queryForList(
                        "select 1 from trading.discrepancy_settlement where discrepancy_document_id = ?",
                        Integer.class,
                        discrepancyId)
                .isEmpty();
        if (settled) {
            throw new ProblemException("m4.discrepancy.settled_already");
        }

        Attachments.PresignedUpload upload = attachments.presignUpload(
                discrepancyId, null, command.contentType(), command.contentLength(), null, scope);

        jdbc.update(
                "insert into trading.discrepancy_photo (attachment_id, discrepancy_document_id, added_by, added_at) values (?, ?, ?, ?)",
                upload.attachmentId(),
                discrepancyId,
                scope.userId(),
                Timestamp.from(clock.now()));

        audit.record(
                AUDIT_PHOTO,
                Subject.of("discrepancy", discrepancyId),
                null,
                Map.of("attachmentId", upload.attachmentId()),
                scope);

        events.publish(new DiscrepancyPhotoAdded(
                discrepancyId, header.ownerEntityId(), header.counterpartyEntityId(), upload.attachmentId()));

        return upload;
    }
}
