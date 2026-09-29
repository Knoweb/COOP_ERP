package lk.coopfed.knoweb.m4trading.internal.transfer;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m4trading.api.RequestTransfer;
import lk.coopfed.knoweb.m4trading.api.TransferRequestLine;
import lk.coopfed.knoweb.m4trading.api.TransferRequested;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RequestLateralTransfer (24A section 6; doc 24 section 4.7): a shop asks for stock from another
 * location of its own society, usually the society's stores.
 *
 * <p>Guards, in order: an OWN scope (a shop session at its shop, or entity-wide); the asking shop,
 * which is the session's own location when it has one ({@code m4.transfer.location_not_in_scope});
 * a source, when named, that is not the shop ({@code m4.transfer.same_location}); lines ({@code
 * m4.transfer.lines_required}), each an item M2 knows ({@code m4.transfer.sku_unknown}) once ({@code
 * m4.transfer.sku_duplicate}), a quantity above zero with at most three decimals ({@code
 * m4.transfer.qty_invalid}). A shop session cannot read the stores (M1), so the source may be left
 * to the society, and that it is a location of the society is checked when the society decides.
 *
 * <p>Mutation: {@code transfer_request} and its lines, at the asking shop. Audit TRANSFER_REQUESTED;
 * event transfer_request.requested.v1.
 */
@Service
@CommandHandler(permission = "shop.transfer.request")
public class RequestTransferHandler implements Handles<RequestTransfer, UUID> {

    static final String AUDIT_REQUESTED = "TRANSFER_REQUESTED";

    private final JdbcTemplate jdbc;
    private final CatalogueQueries catalogue;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    RequestTransferHandler(
            JdbcTemplate jdbc,
            CatalogueQueries catalogue,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.catalogue = catalogue;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RequestTransfer command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireOwnScope(scope);
        UUID to = command.toLocationId() != null ? command.toLocationId() : scope.locationId();
        TradingGuards.required(to, "toLocationId");
        if (scope.locationId() != null && !scope.locationId().equals(to)) {
            throw new ProblemException("m4.transfer.location_not_in_scope");
        }
        // The shop may leave the source to the society: a shop session sees no other location (M1).
        UUID from = command.fromLocationId();
        if (to.equals(from)) {
            throw new ProblemException("m4.transfer.same_location");
        }
        if (command.lines().isEmpty()) {
            throw new ProblemException("m4.transfer.lines_required");
        }
        Set<UUID> seen = new HashSet<>();
        for (RequestTransfer.Line line : command.lines()) {
            if (line == null
                    || line.skuId() == null
                    || catalogue.getSku(line.skuId(), scope).isEmpty()) {
                throw new ProblemException("m4.transfer.sku_unknown");
            }
            if (!seen.add(line.skuId())) {
                throw new ProblemException("m4.transfer.sku_duplicate", Map.of("skuId", line.skuId()));
            }
            BigDecimal qty = line.qty();
            if (qty == null || qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 3) {
                throw new ProblemException("m4.transfer.qty_invalid", Map.of("skuId", line.skuId()));
            }
        }

        UUID requestId = Ids.next();
        String reason = command.reason() == null || command.reason().isBlank()
                ? null
                : command.reason().strip();
        jdbc.update(
                """
                insert into trading.transfer_request (request_id, owner_entity_id, location_id, from_location_id, reason,
                    requested_by, requested_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """,
                requestId,
                scope.entityId(),
                to,
                from,
                reason,
                scope.userId(),
                Timestamp.from(clock.now()));
        List<TransferRequestLine> lines = new ArrayList<>();
        int lineNo = 0;
        for (RequestTransfer.Line line : command.lines()) {
            UUID lineId = Ids.next();
            lineNo++;
            jdbc.update(
                    """
                    insert into trading.transfer_request_line (line_id, request_id, owner_entity_id, location_id,
                        from_location_id, line_no, sku_id, qty)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    lineId,
                    requestId,
                    scope.entityId(),
                    to,
                    from,
                    lineNo,
                    line.skuId(),
                    line.qty());
            lines.add(new TransferRequestLine(lineId, line.skuId(), line.qty()));
        }

        audit.record(
                AUDIT_REQUESTED,
                Subject.of("transfer_request", requestId),
                null,
                auditAfter(from, to, lines.size()),
                scope);
        events.publish(new TransferRequested(requestId, scope.entityId(), from, to, List.copyOf(lines)));
        return requestId;
    }

    private static Map<String, Object> auditAfter(UUID from, UUID to, int lines) {
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("status", TransferRequestReads.REQUESTED);
        after.put("fromLocationId", from);
        after.put("toLocationId", to);
        after.put("lines", lines);
        return after;
    }
}
