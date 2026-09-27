package lk.coopfed.knoweb.m4trading.internal.grn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.BusinessDate;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m4trading.api.CaptureGrn;
import lk.coopfed.knoweb.m4trading.api.GrnCaptured;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnDrops.DropToReceive;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnDrops.ExpectedItem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CaptureGrn (24A section 6), the web capture of a delivery. Guards, in order: the receiver's OWN
 * scope (entity-wide, or at the location it receives at); a location of the caller's own entity
 * (M1), the session's location when it has one; a drop of an issued delivery note, billed to the
 * caller and shipped to that location, with no GRN of its own yet; lines of items on the drop,
 * each once, in the item's unit, received ≥ 0 and damaged ≤ received. An item of the drop that is
 * not counted is received as zero (a short line).
 *
 * <p>Mutation: the kernel draft at the location (one line per item, at the trade price of the
 * delivery, the delivery line as reference), {@code doc_grn}, {@code doc_grn_line} (expected from
 * the drop, received, damaged, the batch data keyed). Audit GRN_CAPTURED; event grn.captured.v1.
 * A local supply (a GRN with a supplier and no drop) and the till's GRN bundle (K-08-F4) are
 * deferred for the demo.
 */
@Service
@CommandHandler(permission = "shop.grn.confirm")
public class CaptureGrnHandler implements Handles<CaptureGrn, UUID> {

    static final String AUDIT_CAPTURED = "GRN_CAPTURED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final PartyQueries parties;
    private final GrnDrops drops;
    private final BusinessDate businessDate;
    private final AuditFacade audit;
    private final EventPublisher events;

    CaptureGrnHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            PartyQueries parties,
            GrnDrops drops,
            BusinessDate businessDate,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.parties = parties;
        this.drops = drops;
        this.businessDate = businessDate;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(CaptureGrn command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireOwnScope(scope);
        UUID receiver = scope.entityId();
        UUID locationId = TradingGuards.required(command.locationId(), "locationId");
        if (scope.locationId() != null && !scope.locationId().equals(locationId)) {
            throw new ProblemException("scope.invalid");
        }
        LocationView location = parties.getLocation(locationId, scope)
                .filter(found -> receiver.equals(found.ownerEntityId()))
                .orElseThrow(() -> new ProblemException("m4.grn.location_unknown"));
        UUID dropId = TradingGuards.required(command.dropId(), "dropId");
        DropToReceive drop = drops.drop(dropId);
        if (!receiver.equals(drop.billToEntityId())) {
            throw new ProblemException("m4.grn.not_receiver");
        }
        if (!location.locationId().equals(drop.shipToLocationId())) {
            throw new ProblemException("m4.grn.location_mismatch");
        }
        if (drops.alreadyCaptured(dropId)) {
            throw new ProblemException("m4.grn.drop_already_captured");
        }
        if (command.lines() == null || command.lines().isEmpty()) {
            throw new ProblemException("m4.grn.lines_required");
        }

        Map<UUID, CaptureGrn.Line> counted = new LinkedHashMap<>();
        for (CaptureGrn.Line line : command.lines()) {
            UUID skuId = TradingGuards.required(line.skuId(), "skuId");
            ExpectedItem expected = drop.items().get(skuId);
            if (expected == null) {
                throw new ProblemException("m4.grn.sku_not_on_drop", Map.of("skuId", skuId));
            }
            if (counted.put(skuId, line) != null) {
                throw new ProblemException("m4.grn.line_duplicate", Map.of("skuId", skuId));
            }
            String uom = line.uomCode() == null
                    ? expected.uomCode()
                    : line.uomCode().strip().toUpperCase();
            if (!uom.equals(expected.uomCode())) {
                throw new ProblemException("m4.grn.uom_invalid", Map.of("skuId", skuId));
            }
            BigDecimal received = line.receivedQty();
            BigDecimal damaged = line.damagedQty() == null ? BigDecimal.ZERO : line.damagedQty();
            if (received == null || received.signum() < 0 || damaged.signum() < 0 || damaged.compareTo(received) > 0) {
                throw new ProblemException("m4.grn.line_quantities", Map.of("skuId", skuId));
            }
        }

        UUID grnId = Ids.next();
        LocalDate receivedOn = command.receivedOn() != null ? command.receivedOn() : businessDate.current(locationId);
        List<DocumentLineRecord> kernelLines = new ArrayList<>();
        List<Object[]> extensionRows = new ArrayList<>();
        int lineNo = 0;
        for (ExpectedItem expected : drop.items().values()) {
            CaptureGrn.Line line = counted.get(expected.skuId());
            BigDecimal received = line == null ? BigDecimal.ZERO : line.receivedQty();
            BigDecimal damaged = line == null || line.damagedQty() == null ? BigDecimal.ZERO : line.damagedQty();
            UUID lineId = Ids.next();
            lineNo++;
            BigDecimal price = expected.unitPrice();
            kernelLines.add(TradingDocuments.line(
                    lineId,
                    grnId,
                    lineNo,
                    expected.skuId(),
                    null,
                    expected.uomCode(),
                    received,
                    price,
                    null,
                    null,
                    price == null ? null : price.multiply(received).setScale(2, RoundingMode.HALF_UP),
                    price,
                    expected.deliveryLineId()));
            extensionRows.add(new Object[] {
                lineId,
                expected.expectedQty(),
                received,
                damaged,
                line == null ? null : blankToNull(line.batchNo()),
                line == null ? null : line.manufactureDate(),
                line == null ? null : line.expiryDate(),
                line == null ? null : line.printedMrp()
            });
        }

        documents.save(TradingDocuments.draft(
                grnId,
                GrnReads.GRN,
                receiver,
                drop.sellerEntityId(),
                locationId,
                scope.userId(),
                drop.deliveryNoteId(),
                null));
        documents.saveLines(grnId, kernelLines);
        jdbc.update(
                """
                insert into trading.doc_grn (document_id, receiver_entity_id, receiver_location_id, seller_entity_id,
                    relationship_id, drop_id, delivery_document_id, received_on, counted_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                grnId,
                receiver,
                locationId,
                drop.sellerEntityId(),
                drop.relationshipId(),
                dropId,
                drop.deliveryNoteId(),
                receivedOn,
                scope.userId());
        for (Object[] row : extensionRows) {
            jdbc.update(
                    """
                    insert into trading.doc_grn_line (line_id, document_id, expected_qty, received_qty, damaged_qty,
                        batch_no, manufacture_date, expiry_date, printed_mrp)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    row[0],
                    grnId,
                    row[1],
                    row[2],
                    row[3],
                    row[4],
                    row[5],
                    row[6],
                    row[7]);
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("grnId", grnId);
        after.put("dropId", dropId);
        after.put("locationId", locationId);
        after.put("lines", kernelLines.size());
        audit.record(AUDIT_CAPTURED, Subject.of("grn", grnId), null, after, scope);

        events.publish(new GrnCaptured(
                grnId,
                receiver,
                locationId,
                drop.sellerEntityId(),
                dropId,
                drop.deliveryNoteId(),
                null,
                receivedOn,
                kernelLines.size()));
        return grnId;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
