package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchQueries;
import lk.coopfed.knoweb.m2catalogue.query.BatchView;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceChanged;
import lk.coopfed.knoweb.m5inventory.api.OpeningBalanceLine;
import lk.coopfed.knoweb.m5inventory.api.PrepareOpeningBalance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PrepareOpeningBalance (25A section 6.3; doc 25 sections 3.7 and 4.7, "– to DRAFT"): the counted
 * stock of a location, with batch data and cost, before anyone signs it.
 *
 * <p>Guards, in order: an OWN scope ({@code m5.scope.own_required}); the location one of the
 * scope entity's that the scope reads ({@code m5.location.not_in_scope}); no stock has moved at it
 * ({@code m5.opening.location_has_stock}: an opening balance is the first stock of a location,
 * R-02); none in preparation for it ({@code m5.opening.already_open}); at least one line
 * ({@code m5.opening.lines_required}); each line's batch known to M2 ({@code m5.batch.not_found}),
 * its quantity above zero with at most three decimals and its cost zero or more with at most four
 * ({@code m5.opening.line_invalid}).
 *
 * <p>25A's "location ONBOARDING" guard is not applied: the demo loads the opening stock of
 * locations that are active already (recorded in the README). Audit {@code OPB_PREPARED}; event
 * {@code opening_balance.changed.v1} with status DRAFT.
 */
@Service
@CommandHandler(permission = "inv.opening.prepare")
class PrepareOpeningBalanceHandler implements Handles<PrepareOpeningBalance, UUID> {

    static final String AUDIT_PREPARED = "OPB_PREPARED";

    private final OpeningBalanceStore store;
    private final PartyQueries party;
    private final BatchQueries batches;
    private final JdbcTemplate jdbc;
    private final AuditFacade audit;
    private final EventPublisher events;

    PrepareOpeningBalanceHandler(
            OpeningBalanceStore store,
            PartyQueries party,
            BatchQueries batches,
            JdbcTemplate jdbc,
            AuditFacade audit,
            EventPublisher events) {
        this.store = store;
        this.party = party;
        this.batches = batches;
        this.jdbc = jdbc;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(PrepareOpeningBalance command, ScopeContext scope) {
        if (scope == null || scope.policyClass() != PolicyClass.OWN || scope.entityId() == null) {
            throw new ProblemException("m5.scope.own_required");
        }
        UUID location = command.locationId();
        if (location == null
                || party.getLocation(location, scope)
                        .filter(l -> scope.entityId().equals(l.ownerEntityId()))
                        .isEmpty()) {
            throw new ProblemException("m5.location.not_in_scope", Map.of("locationId", String.valueOf(location)));
        }
        if (store.locationHasMovements(location)) {
            throw new ProblemException("m5.opening.location_has_stock", Map.of("locationId", location));
        }
        if (store.openBalanceAt(location)) {
            throw new ProblemException("m5.opening.already_open", Map.of("locationId", location));
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ProblemException("m5.opening.lines_required");
        }
        List<UUID> skus = new ArrayList<>();
        for (OpeningBalanceLine line : command.lines()) {
            requireLine(line);
            BatchView batch = batches.getBatch(line.batchId(), scope)
                    .orElseThrow(() -> new ProblemException("m5.batch.not_found", Map.of("batchId", line.batchId())));
            skus.add(batch.skuId());
        }

        UUID id = Ids.next();
        jdbc.update(
                "insert into inventory.opening_balance (opening_balance_id, owner_entity_id, location_id, prepared_by)"
                        + " values (?, ?, ?, ?)",
                id,
                scope.entityId(),
                location,
                scope.userId());
        for (int i = 0; i < command.lines().size(); i++) {
            OpeningBalanceLine line = command.lines().get(i);
            jdbc.update(
                    """
                    insert into inventory.opening_balance_line
                        (line_id, opening_balance_id, owner_entity_id, location_id, line_no, batch_id, sku_id, condition,
                         qty, unit_cost)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    id,
                    scope.entityId(),
                    location,
                    i + 1,
                    line.batchId(),
                    skus.get(i),
                    line.condition() == null ? "GOOD" : line.condition().name(),
                    line.qty(),
                    line.unitCost());
        }

        audit.record(
                AUDIT_PREPARED,
                Subject.of("opening_balance", id),
                null,
                Map.of(
                        "locationId",
                        location,
                        "status",
                        "DRAFT",
                        "lines",
                        command.lines().size()),
                scope);
        events.publish(new OpeningBalanceChanged(id, scope.entityId(), location, "DRAFT"));
        return id;
    }

    private static void requireLine(OpeningBalanceLine line) {
        boolean valid = line != null
                && line.batchId() != null
                && line.qty() != null
                && line.qty().signum() > 0
                && line.qty().stripTrailingZeros().scale() <= 3
                && line.unitCost() != null
                && line.unitCost().signum() >= 0
                && line.unitCost().stripTrailingZeros().scale() <= 4;
        if (!valid) {
            throw new ProblemException("m5.opening.line_invalid");
        }
    }
}
