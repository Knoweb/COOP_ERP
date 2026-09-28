package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.AddWriteOffPhoto;
import lk.coopfed.knoweb.m5inventory.api.WriteOffPhotoAdded;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A photograph for a draft write-off (25A section 6.3, "photos referenced"; 19A section 9): the
 * kernel authorises one upload against the WOF draft and records the attachment PENDING; the
 * client PUTs the bytes to the returned URL; the kernel's verifier makes it COMPLETE. The
 * write-off remembers the attachment so its guards can ask for it.
 *
 * <p>Guards, in order: an OWN scope; the write-off visible ({@code m5.writeoff.not_found}); DRAFT
 * ({@code m5.writeoff.not_draft}: evidence is fixed once the document is issued); the kernel's
 * own guards on type and size ({@code attachment.*}).
 *
 * <p>Mutation: the kernel's PENDING attachment and the write-off's photo row. Audit
 * {@code WRITEOFF_PHOTO_ADDED} (the kernel records its own); event {@code writeoff.photo_added.v1}.
 */
@Service
@CommandHandler(permission = "inv.writeoff.request")
class AddWriteOffPhotoHandler implements Handles<AddWriteOffPhoto, Attachments.PresignedUpload> {

    static final String AUDIT_PHOTO = "WRITEOFF_PHOTO_ADDED";

    private final WriteOffStore store;
    private final Attachments attachments;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    AddWriteOffPhotoHandler(
            WriteOffStore store,
            Attachments attachments,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.attachments = attachments;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Attachments.PresignedUpload handle(AddWriteOffPhoto command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        WriteOffStore.Header writeOff = store.lock(command.writeOffId());
        if (!"DRAFT".equals(writeOff.status())) {
            throw new ProblemException("m5.writeoff.not_draft");
        }

        Attachments.PresignedUpload upload = attachments.presignUpload(
                writeOff.writeOffId(), null, command.contentType(), command.contentLength(), null, scope);
        jdbc.update(
                """
                insert into inventory.write_off_photo
                    (attachment_id, write_off_id, owner_entity_id, location_id, added_by, added_at)
                values (?, ?, ?, ?, ?, ?)
                """,
                upload.attachmentId(),
                writeOff.writeOffId(),
                writeOff.ownerEntityId(),
                writeOff.locationId(),
                scope.userId(),
                Timestamp.from(clock.instant()));

        audit.record(
                AUDIT_PHOTO,
                Subject.of("write_off", writeOff.writeOffId()),
                null,
                Map.of("attachmentId", upload.attachmentId()),
                scope);
        events.publish(new WriteOffPhotoAdded(writeOff.writeOffId(), writeOff.ownerEntityId(), upload.attachmentId()));
        return upload;
    }
}
