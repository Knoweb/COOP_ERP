package lk.coopfed.knoweb.m4trading.internal.claim;

import java.sql.Timestamp;
import java.util.Map;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.AddClaimPhoto;
import lk.coopfed.knoweb.m4trading.api.ClaimPhotoAdded;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A photograph for a raised claim (doc 24 section 4.5: "photographs attached; pending upload
 * allowed, must be COMPLETE before decision"; 19A section 9), as a write-off's photograph in M5:
 * the kernel authorises one upload against the claim document and records the attachment PENDING;
 * the client PUTs the bytes; the kernel's verifier makes it COMPLETE.
 *
 * <p>Guards, in order: the buyer's entity-wide OWN scope; the caller's own claim ({@code
 * m4.claim.not_found}); not decided ({@code m4.claim.decided}: the evidence is fixed once the
 * seller has decided); the kernel's own guards on type and size ({@code attachment.*}).
 *
 * <p>Mutation: the kernel's PENDING attachment and the claim's photo row. Audit CLAIM_PHOTO_ADDED
 * (the kernel records its own); event claim.photo_added.v1.
 */
@Service
@CommandHandler(permission = "del.claim.raise")
public class AddClaimPhotoHandler implements Handles<AddClaimPhoto, Attachments.PresignedUpload> {

    static final String AUDIT_PHOTO = "CLAIM_PHOTO_ADDED";

    private final JdbcTemplate jdbc;
    private final ClaimReads claims;
    private final Attachments attachments;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    AddClaimPhotoHandler(
            JdbcTemplate jdbc,
            ClaimReads claims,
            Attachments attachments,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.claims = claims;
        this.attachments = attachments;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public Attachments.PresignedUpload handle(AddClaimPhoto command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        ClaimReads.Claim claim = claims.claim(command.claimId())
                .filter(found -> scope.entityId().equals(found.header().ownerEntityId()))
                .orElseThrow(() -> new ProblemException("m4.claim.not_found"));
        if (claims.decision(command.claimId()).isPresent()) {
            throw new ProblemException("m4.claim.decided");
        }

        Attachments.PresignedUpload upload = attachments.presignUpload(
                command.claimId(), null, command.contentType(), command.contentLength(), null, scope);
        jdbc.update(
                "insert into trading.claim_photo (attachment_id, claim_document_id, added_by, added_at) values (?, ?, ?, ?)",
                upload.attachmentId(),
                command.claimId(),
                scope.userId(),
                Timestamp.from(clock.now()));

        audit.record(
                AUDIT_PHOTO,
                Subject.of("claim", command.claimId()),
                null,
                Map.of("attachmentId", upload.attachmentId()),
                scope);
        events.publish(new ClaimPhotoAdded(
                command.claimId(),
                claim.header().ownerEntityId(),
                claim.header().counterpartyEntityId(),
                upload.attachmentId()));
        return upload;
    }
}
