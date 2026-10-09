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
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Sod;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m5inventory.api.ApproveWriteOff;
import lk.coopfed.knoweb.m5inventory.api.LotCondition;
import lk.coopfed.knoweb.m5inventory.api.Movement;
import lk.coopfed.knoweb.m5inventory.api.MovementType;
import lk.coopfed.knoweb.m5inventory.api.PostMovements;
import lk.coopfed.knoweb.m5inventory.api.StockLedger;
import lk.coopfed.knoweb.m5inventory.api.WriteOffPosted;
import lk.coopfed.knoweb.m5inventory.internal.control.ControlPolicy;
import lk.coopfed.knoweb.m5inventory.internal.control.StockOnHand;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ApproveWriteOff (25A section 6.3: "WITNESSED; approver ≠ requester; value (Σ qty × avg cost) ≤
 * approver band; MFA → APPROVED → WRITE_OFF movements → POSTED"; doc 25 flow 6.4). The requester
 * never approves; since wave 2 (M5-10, CR-25A-1 item 3) neither does the in-person witness: only
 * the remote witness of a single-staff location (the MPCS accountant, flow 6.4) may also approve.
 *
 * <p>Guards, in order: an OWN scope; the write-off visible ({@code m5.writeoff.not_found});
 * WITNESSED ({@code m5.writeoff.not_witnessed}); the approver not the requester
 * ({@code m5.writeoff.approver_is_requester}); the approver not the in-person witness
 * ({@code m5.writeoff.approver_is_witness}); the kernel's {@code sod.same_person} for the pair
 * inv.writeoff.request / inv.writeoff.approve; each lot, locked in the ledger's order, still
 * holding its quantity ({@code m5.writeoff.insufficient_stock}, M5-12); the value, recomputed now
 * (the entity average, else the lot's cost; a line of no cost routes to band 2), within the
 * approver's limit, which fails closed ({@code m5.approval.limit_exceeded}, M5-09). The fresh
 * second factor is the kernel's.
 *
 * <p>Mutation: WRITE_OFF per line, citing the WOF document, at the entity average; POSTED with the
 * approver and the value. The loss is the entity's that owns the lot (ADR-03). Audit
 * {@code WRITEOFF_POSTED}; event {@code writeoff.posted.v1}.
 */
@Service
@CommandHandler(permission = "inv.writeoff.approve", requiresMfa = true)
class ApproveWriteOffHandler implements Handles<ApproveWriteOff, UUID> {

    static final String AUDIT_POSTED = "WRITEOFF_POSTED";

    private final WriteOffStore store;
    private final ControlPolicy policy;
    private final Sod sod;
    private final StockLedger ledger;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one command, each used once
    ApproveWriteOffHandler(
            WriteOffStore store,
            ControlPolicy policy,
            Sod sod,
            StockLedger ledger,
            JdbcTemplate jdbc,
            Clock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.policy = policy;
        this.sod = sod;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(ApproveWriteOff command, ScopeContext scope) {
        ControlPolicy.requireOwn(scope);
        WriteOffStore.Header writeOff = store.lock(command.writeOffId());
        if (!"WITNESSED".equals(writeOff.status())) {
            throw new ProblemException("m5.writeoff.not_witnessed");
        }
        if (scope.userId() == null || scope.userId().equals(writeOff.requestedBy())) {
            throw new ProblemException("m5.writeoff.approver_is_requester");
        }
        if (scope.userId().equals(writeOff.witnessUserId()) && !writeOff.remoteWitness()) {
            // wave 2, M5-10: three people where three exist. Only a single-staff location's remote
            // witness, who holds the approval and has the photographs as the third eye, may approve.
            throw new ProblemException("m5.writeoff.approver_is_witness");
        }
        sod.assertDistinct(scope, "inv.writeoff.request", "inv.writeoff.approve", writeOff.requestedBy());
        List<WriteOffStore.Line> lines = store.lines(writeOff.writeOffId());
        Map<StockOnHand.LotRef, BigDecimal> held = store.lockLots(writeOff.locationId(), lines);
        Map<StockOnHand.LotRef, BigDecimal> wanted = new LinkedHashMap<>();
        for (WriteOffStore.Line line : lines) {
            wanted.merge(new StockOnHand.LotRef(line.batchId(), line.condition()), line.qty(), BigDecimal::add);
        }
        wanted.forEach((lot, qty) -> {
            if (held.get(lot).compareTo(qty) < 0) {
                throw new ProblemException("m5.writeoff.insufficient_stock", Map.of("batchId", lot.batchId()));
            }
        });
        WriteOffStore.Valuation valuation = store.value(lines, writeOff.locationId(), scope);
        BigDecimal value = valuation.value();
        policy.requireWithinLimit(
                value, valuation.zeroCostLine(), "inv.writeoff.approve", writeOff.locationId(), scope);

        List<Movement> movements = lines.stream()
                .map(line -> new Movement(
                        writeOff.locationId(),
                        line.batchId(),
                        LotCondition.valueOf(line.condition()),
                        MovementType.WRITE_OFF,
                        line.qty().negate(),
                        null,
                        line.lineId()))
                .toList();
        ledger.post(new PostMovements(writeOff.writeOffId(), null, null, movements), scope);
        int band = policy.band(value, valuation.zeroCostLine(), scope);
        jdbc.update(
                """
                update inventory.write_off
                   set status = 'POSTED', approver_user_id = ?, decided_at = ?, value = ?, band = ?
                 where write_off_id = ?
                """,
                scope.userId(),
                Timestamp.from(clock.instant()),
                value,
                band,
                writeOff.writeOffId());

        audit.record(
                AUDIT_POSTED,
                Subject.of("write_off", writeOff.writeOffId()),
                Map.of("status", "WITNESSED"),
                Map.of(
                        "status",
                        "POSTED",
                        "category",
                        writeOff.category().name(),
                        "value",
                        value.toPlainString(),
                        "band",
                        band),
                scope);
        events.publish(new WriteOffPosted(
                writeOff.writeOffId(),
                writeOff.ownerEntityId(),
                writeOff.locationId(),
                writeOff.category().name(),
                value,
                scope.userId()));
        return writeOff.writeOffId();
    }
}
