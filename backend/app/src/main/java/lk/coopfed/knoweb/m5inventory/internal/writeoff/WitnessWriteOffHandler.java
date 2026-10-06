package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.PermissionResolver;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m5inventory.api.WitnessWriteOff;
import lk.coopfed.knoweb.m5inventory.api.WriteOffWitnessed;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * WitnessWriteOff (25A section 6.3: "REQUESTED; witness ≠ requester; attachments COMPLETE; remote
 * witness allowed only when the location is single-staff (config) and the witness holds approve →
 * WITNESSED (remote_witness flag)"; doc 25 section 3.8, DR-4).
 *
 * <p>Accepted on the architect's delegation: a witness at a single-staff location is a remote
 * witness (nobody else is there to see the loss in person), which the Federation must allow
 * ({@code inventory.remote_witness_allowed}) and which only an approver may give; anywhere else the
 * witness is in person.
 *
 * <p>Guards, in order: an OWN scope; the write-off visible ({@code m5.writeoff.not_found});
 * REQUESTED ({@code m5.writeoff.not_requested}); the witness not the requester
 * ({@code m5.writeoff.witness_is_requester}); no photograph still PENDING, each COMPLETE or FAILED
 * (an upload that never arrived) ({@code m5.writeoff.photos_incomplete}), and at least one COMPLETE
 * where photographs are required ({@code m5.writeoff.photos_required}; wave 2, M5-11: the FAILED ones are named in the audit,
 * and evidence stays fixed after issue); for a remote witness, remote witnessing allowed and the
 * witness holding inv.writeoff.approve ({@code m5.writeoff.remote_witness_not_allowed}).
 *
 * <p>Mutation: WITNESSED with the witness, the time and the remote flag. Audit
 * {@code WRITEOFF_WITNESSED} naming the witness; event {@code writeoff.witnessed.v1}.
 */
@Service
@CommandHandler(permission = "inv.writeoff.witness")
class WitnessWriteOffHandler implements Handles<WitnessWriteOff, UUID> {

    static final String AUDIT_WITNESSED = "WRITEOFF_WITNESSED";

    private final WriteOffStore store;
    private final ControlPolicy policy;
    private final Attachments attachments;
    private final PermissionResolver permissions;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one command, each used once
    WitnessWriteOffHandler(
            WriteOffStore store,
            ControlPolicy policy,
            Attachments attachments,
            PermissionResolver permissions,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.attachments = attachments;
        this.permissions = permissions;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(WitnessWriteOff command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        WriteOffStore.Header writeOff = store.lock(command.writeOffId());
        if (!"REQUESTED".equals(writeOff.status())) {
            throw new ProblemException("m5.writeoff.not_requested");
        }
        if (scope.userId() == null || scope.userId().equals(writeOff.requestedBy())) {
            throw new ProblemException("m5.writeoff.witness_is_requester");
        }
        LocationView location = policy.requireEntityLocation(writeOff.locationId(), scope);
        // wave 2, M5-11: an upload that never arrived (FAILED) does not hold the write-off for ever;
        // one still on its way (PENDING) does, and where photographs are required one must be there.
        List<UUID> photos = store.photos(writeOff.writeOffId());
        List<UUID> failed = new ArrayList<>();
        int complete = 0;
        for (UUID photo : photos) {
            String status = attachments.status(photo).orElse("PENDING");
            if ("COMPLETE".equals(status)) {
                complete++;
            } else if ("FAILED".equals(status)) {
                failed.add(photo);
            } else {
                throw new ProblemException("m5.writeoff.photos_incomplete", Map.of("attachmentId", photo));
            }
        }
        if (complete == 0 && policy.photosRequired(writeOff.category(), location, scope)) {
            throw new ProblemException("m5.writeoff.photos_required");
        }
        boolean remote = policy.singleStaff(location, scope);
        if (remote && (!policy.remoteWitnessAllowed(scope) || !permissions.allows(scope, "inv.writeoff.approve"))) {
            throw new ProblemException("m5.writeoff.remote_witness_not_allowed");
        }

        jdbc.update(
                """
                update inventory.write_off
                   set status = 'WITNESSED', witness_user_id = ?, witnessed_at = ?, remote_witness = ?
                 where write_off_id = ?
                """,
                scope.userId(),
                Timestamp.from(clock.instant()),
                remote,
                writeOff.writeOffId());

        audit.record(
                AUDIT_WITNESSED,
                Subject.of("write_off", writeOff.writeOffId()),
                Map.of("status", "REQUESTED"),
                Map.of("status", "WITNESSED", "remoteWitness", remote, "photos", photos.size(), "failedPhotos", failed),
                scope,
                null,
                scope.userId());
        events.publish(new WriteOffWitnessed(
                writeOff.writeOffId(), writeOff.ownerEntityId(), writeOff.locationId(), scope.userId(), remote));
        return writeOff.writeOffId();
    }
}
