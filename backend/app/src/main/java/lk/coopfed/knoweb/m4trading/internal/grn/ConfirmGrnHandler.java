package lk.coopfed.knoweb.m4trading.internal.grn;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.NumberingService;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.LocationView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m2catalogue.api.BatchRegistration;
import lk.coopfed.knoweb.m2catalogue.api.RegisterBatch;
import lk.coopfed.knoweb.m2catalogue.api.RegisteredBatch;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m4trading.api.ConfirmGrn;
import lk.coopfed.knoweb.m4trading.api.DiscrepancyLine;
import lk.coopfed.knoweb.m4trading.api.DiscrepancyRaised;
import lk.coopfed.knoweb.m4trading.api.GrnConfirmed;
import lk.coopfed.knoweb.m4trading.api.GrnLineConfirmed;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.Posting;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.grn.DiscrepancyDetector.Finding;
import lk.coopfed.knoweb.m4trading.internal.grn.DiscrepancyDetector.Variance;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads.Grn;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads.GrnLine;
import lk.coopfed.knoweb.m4trading.internal.posting.PostingMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ConfirmGrn, the pivot (24A section 6.1; AGENTS.md idea 2: ownership passes here). Guards, in
 * order: the receiver's OWN scope, at the GRN's location when the session has one; its own DRAFT
 * GRN; per line with something received, the printed MRP of an item that has one and the expiry
 * of an expiry-tracked item (M2).
 *
 * <p>Mutation, one transaction: the number (the shop's LOCATION series when M1 registered one,
 * the receiver's ENTITY series otherwise, 24B); DRAFT to ISSUED to CONFIRMED (CR-24A-1 item 6);
 * per line received, the batch through M2's RegisterBatch, the internal command (CR-19A-6, #138),
 * with the batch id and the unit cost written on the extension line (CR-24A-1 item 4); when
 * {@link DiscrepancyDetector} finds a variance, the discrepancy document at the GRN's location
 * (#142, #148) from the receiver's ENTITY series, its lines, a DISPUTES link to the GRN and its
 * window from the relationship. Audit GRN_CONFIRMED (and DISCREPANCY_RAISED); events
 * grn.confirmed.v1, whose lines carry the batch ids (frozen at M4-05), discrepancy.raised.v1, and
 * journal.postings_ready.v1 with the receiver's own postings (GRN GOODS BUYER: inventory against the
 * GRN accrual, at {@link #cost}; a GRN of a local supplier is the receiver's purchase too), in the
 * receiver's scope, so the buyer's books have their goods (wave 2, CR-24A-3 item 5, M4MONEY-10).
 */
@Service
@CommandHandler(permission = "shop.grn.confirm")
public class ConfirmGrnHandler implements Handles<ConfirmGrn, String> {

    static final String AUDIT_CONFIRMED = "GRN_CONFIRMED";
    static final String AUDIT_DISCREPANCY = "DISCREPANCY_RAISED";
    static final String RAISED = "RAISED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final NumberingService numbering;
    private final TradingSeries series;
    private final GrnReads reads;
    private final PartyQueries parties;
    private final RelationshipQueries relationships;
    private final CatalogueQueries catalogue;
    private final BatchRegistration batches;
    private final PostingMapper postings;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    ConfirmGrnHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            NumberingService numbering,
            TradingSeries series,
            GrnReads reads,
            PartyQueries parties,
            RelationshipQueries relationships,
            CatalogueQueries catalogue,
            BatchRegistration batches,
            PostingMapper postings,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.numbering = numbering;
        this.series = series;
        this.reads = reads;
        this.parties = parties;
        this.relationships = relationships;
        this.catalogue = catalogue;
        this.batches = batches;
        this.postings = postings;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public String handle(ConfirmGrn command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireOwnScope(scope);
        UUID grnId = TradingGuards.required(command.grnId(), "grnId");
        Grn grn = reads.grn(grnId)
                .filter(found -> GrnReads.GRN.equals(found.header().docTypeCode()))
                .orElseThrow(() -> new ProblemException("m4.grn.not_found"));
        if (!scope.entityId().equals(grn.header().ownerEntityId())) {
            throw new ProblemException("m4.grn.not_receiver");
        }
        if (scope.locationId() != null && !scope.locationId().equals(grn.receiverLocationId())) {
            throw new ProblemException("scope.invalid");
        }
        DocumentRecord draft = documents.findByIdForUpdate(grnId).orElseThrow();
        if (!TradingDocuments.DRAFT.equals(draft.status())) {
            throw new ProblemException("m4.grn.not_draft");
        }
        for (GrnLine line : grn.lines()) {
            if (line.receivedQty().signum() <= 0) {
                continue;
            }
            SkuView sku = catalogue
                    .getSku(line.skuId(), scope)
                    .orElseThrow(() -> new ProblemException("m4.order.sku_not_found", Map.of("skuId", line.skuId())));
            if (sku.hasPrintedMrp() && line.printedMrp() == null) {
                throw new ProblemException("m4.grn.mrp_required", Map.of("skuId", line.skuId()));
            }
            if (sku.expiryTracked() && line.expiryDate() == null) {
                throw new ProblemException("m4.grn.expiry_required", Map.of("skuId", line.skuId()));
            }
        }

        // 1. the number: the shop's LOCATION series (M1 registers it with the shop), else the ENTITY series.
        LocationView location = parties.getLocation(grn.receiverLocationId(), scope)
                .orElseThrow(() -> new ProblemException("m4.grn.location_unknown"));
        boolean shopSeries = "SHOP".equals(location.locationType())
                && !numbering
                        .activeSeriesOf(scope.entityId(), location.locationId(), null)
                        .isEmpty();
        if (!shopSeries) {
            series.ensureEntitySeries(GrnReads.GRN, scope);
        }
        DocumentRecord issued = issuance.issue(draft, documents.findLines(grnId), scope);
        documents.addStateTransition(
                clock.transition(grnId, TradingDocuments.ISSUED, GrnReads.CONFIRMED, scope.userId(), null, null),
                scope);

        // 2. batches (M2), inside this transaction; the batch id and the cost on the extension line.
        Instant confirmedAt = clock.now();
        List<GrnLineConfirmed> confirmedLines = new ArrayList<>();
        Map<UUID, UUID> batchOfLine = new LinkedHashMap<>();
        for (GrnLine line : grn.lines()) {
            UUID batchId = null;
            String batchNo = line.batchNo();
            if (line.receivedQty().signum() > 0) {
                RegisteredBatch batch = batches.register(
                        new RegisterBatch(
                                line.skuId(),
                                null,
                                line.batchNo(),
                                line.manufactureDate(),
                                line.expiryDate(),
                                line.printedMrp(),
                                grnId,
                                issued.docNumberDisplay(),
                                line.lineNo()),
                        scope);
                batchId = batch.batchId();
                batchNo = batch.batchNo();
                batchOfLine.put(line.lineId(), batchId);
            }
            jdbc.update(
                    "update trading.doc_grn_line set batch_id = ?, unit_cost = ? where line_id = ?",
                    batchId,
                    line.unitCost(),
                    line.lineId());
            confirmedLines.add(new GrnLineConfirmed(
                    line.lineId(),
                    line.lineNo(),
                    line.skuId(),
                    batchId,
                    batchNo,
                    line.expiryDate(),
                    line.printedMrp(),
                    line.uomCode(),
                    line.expectedQty(),
                    line.receivedQty(),
                    line.damagedQty(),
                    line.unitCost()));
        }
        jdbc.update(
                "update trading.doc_grn set confirmed_by = ?, confirmed_at = ? where document_id = ?",
                scope.userId(),
                Timestamp.from(confirmedAt),
                grnId);

        // 3. the discrepancy, when the count differs from the drop or goods arrived damaged.
        Optional<Finding> finding = DiscrepancyDetector.detect(grn.lines());
        DiscrepancyRaised raised = null;
        if (finding.isPresent()) {
            raised = raise(grn, finding.get(), batchOfLine, scope);
        }

        // 4. the buyer's books (wave 2, CR-24A-3 item 5): GRN GOODS BUYER, inventory against the
        // GRN accrual, at what the invoice will bill for these lines.
        List<Posting> journal = postings.postings(GrnReads.GRN, "GOODS", "BUYER", Map.of("cost", cost(grn.lines())));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", GrnReads.CONFIRMED);
        after.put("docNumber", issued.docNumberDisplay());
        after.put("batches", batchOfLine.size());
        after.put("variance", finding.isPresent());
        audit.record(AUDIT_CONFIRMED, Subject.of("grn", grnId), Map.of("status", TradingDocuments.DRAFT), after, scope);
        if (raised != null) {
            Map<String, Object> disc = new LinkedHashMap<>();
            disc.put("discrepancyId", raised.discrepancyId());
            disc.put("docNumber", raised.docNumberDisplay());
            disc.put("kind", raised.kind());
            disc.put("grnId", grnId);
            audit.record(AUDIT_DISCREPANCY, Subject.of("discrepancy", raised.discrepancyId()), null, disc, scope);
        }

        events.publish(new GrnConfirmed(
                grnId,
                issued.docNumberDisplay(),
                grn.receiverEntityId(),
                grn.receiverLocationId(),
                grn.sellerEntityId(),
                grn.relationshipId(),
                grn.dropId(),
                grn.deliveryDocumentId(),
                grn.supplierId(),
                confirmedAt,
                finding.isPresent(),
                List.copyOf(confirmedLines)));
        if (raised != null) {
            events.publish(raised);
        }
        if (!journal.isEmpty()) {
            // The receiver's side only: no counterparty, so the kernel delivers it to nobody else.
            events.publish(new JournalPostingsReady(
                    grnId,
                    GrnReads.GRN,
                    issued.docNumberDisplay(),
                    scope.entityId(),
                    journal,
                    issued.businessDate(),
                    null));
        }
        return issued.docNumberDisplay();
    }

    /**
     * The cost of what was received: per line, the received quantity at the line's unit cost (the
     * delivery note's price snapshot, the trade price the invoice bills at), rounded to the cent
     * per line exactly as IssueInvoice rounds its net, so the GRN accrual this posts is what the
     * invoice of this GRN clears (doc 24 section 3.9; wave 2, CR-24A-3 item 5).
     */
    static BigDecimal cost(List<GrnLine> lines) {
        BigDecimal cost = BigDecimal.ZERO;
        for (GrnLine line : lines) {
            if (line.receivedQty().signum() <= 0 || line.unitCost() == null) {
                continue;
            }
            cost = cost.add(line.unitCost().multiply(line.receivedQty()).setScale(2, RoundingMode.HALF_UP));
        }
        return cost;
    }

    private DiscrepancyRaised raise(Grn grn, Finding finding, Map<UUID, UUID> batchOfLine, ScopeContext scope) {
        UUID discId = Ids.next();
        List<DocumentLineRecord> kernelLines = new ArrayList<>();
        List<DiscrepancyLine> lines = new ArrayList<>();
        int lineNo = 0;
        for (Variance variance : finding.lines()) {
            GrnLine line = variance.line();
            lineNo++;
            kernelLines.add(TradingDocuments.line(
                    Ids.next(),
                    discId,
                    lineNo,
                    line.skuId(),
                    batchOfLine.get(line.lineId()),
                    line.uomCode(),
                    variance.varianceQty(),
                    line.unitCost(),
                    null,
                    null,
                    null,
                    null,
                    line.lineId()));
            lines.add(new DiscrepancyLine(
                    line.lineId(),
                    line.skuId(),
                    batchOfLine.get(line.lineId()),
                    line.uomCode(),
                    line.expectedQty(),
                    line.receivedQty(),
                    line.damagedQty(),
                    variance.varianceQty()));
        }
        int windowDays = grn.relationshipId() == null
                ? 0
                : relationships
                        .getRelationship(grn.relationshipId(), scope)
                        .map(RelationshipView::discrepancyWindowDays)
                        .orElse(0);
        Instant windowEndsAt = clock.now().plus(Duration.ofDays(windowDays));

        // The discrepancy is raised at the GRN's location (#142, #148), from the receiver's ENTITY series.
        documents.save(TradingDocuments.draft(
                discId,
                GrnReads.DISC,
                grn.receiverEntityId(),
                grn.sellerEntityId(),
                grn.receiverLocationId(),
                scope.userId(),
                grn.header().id(),
                null));
        documents.saveLines(discId, kernelLines);
        series.ensureEntitySeries(GrnReads.DISC, scope);
        DocumentRecord issued =
                issuance.issue(documents.findByIdForUpdate(discId).orElseThrow(), documents.findLines(discId), scope);
        documents.addStateTransition(
                clock.transition(discId, TradingDocuments.ISSUED, RAISED, scope.userId(), finding.kind(), null), scope);
        links.link(discId, grn.header().id(), LinkType.DISPUTES, null, scope);
        jdbc.update(
                """
                insert into trading.doc_discrepancy (document_id, grn_document_id, delivery_document_id, kind,
                    window_ends_at)
                values (?, ?, ?, ?, ?)
                """,
                discId,
                grn.header().id(),
                grn.deliveryDocumentId(),
                finding.kind(),
                Timestamp.from(windowEndsAt));
        for (DiscrepancyLine line : lines) {
            jdbc.update(
                    """
                    insert into trading.doc_discrepancy_line (line_id, document_id, grn_line_id, expected_qty,
                        received_qty, damaged_qty, variance_qty)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    Ids.next(),
                    discId,
                    line.grnLineId(),
                    line.expectedQty(),
                    line.receivedQty(),
                    line.damagedQty() == null ? BigDecimal.ZERO : line.damagedQty(),
                    line.varianceQty());
        }
        return new DiscrepancyRaised(
                discId,
                issued.docNumberDisplay(),
                grn.header().id(),
                grn.deliveryDocumentId(),
                grn.receiverEntityId(),
                grn.receiverLocationId(),
                grn.sellerEntityId(),
                finding.kind(),
                windowEndsAt,
                List.copyOf(lines));
    }
}
