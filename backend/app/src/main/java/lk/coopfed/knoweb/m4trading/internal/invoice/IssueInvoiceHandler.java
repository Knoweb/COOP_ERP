package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipView;
import lk.coopfed.knoweb.m2catalogue.query.CatalogueQueries;
import lk.coopfed.knoweb.m2catalogue.query.SkuView;
import lk.coopfed.knoweb.m4trading.api.InvoiceIssued;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.JournalPostingsReady;
import lk.coopfed.knoweb.m4trading.api.Posting;
import lk.coopfed.knoweb.m4trading.api.TaxRates;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads.Grn;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads.GrnLine;
import lk.coopfed.knoweb.m4trading.internal.posting.PostingMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IssueInvoice (24A section 6). Guards, in order: the seller's entity-wide OWN scope; at least one
 * GRN; each a CONFIRMED GRN received from the caller, all of one buyer under one relationship, none
 * invoiced before (an advisory lock per GRN serialises two invoices of it); the seller's VAT number
 * (M1); per line a VAT rate in force for the item's tax category ({@link TaxRates}).
 *
 * <p>The InvoiceBuilder: one line per GRN line with something received, at the received quantity
 * and the trade price the GRN line carries (the relationship's tier at the ordered quantity, DR-2),
 * VAT per line, rounded to the cent. Mutation: the seller's ENTITY series of INV, the issuance
 * (totals and the content hash frozen), {@code doc_invoice} with the GRNs, the VAT numbers, the tax
 * point and the due date (the relationship's payment terms). Audit INVOICE_ISSUED; events
 * invoice.issued.v1 and journal.postings_ready.v1 (the seller's side, by {@link PostingMapper}).
 */
@Service
@CommandHandler(permission = "bil.invoice.issue")
public class IssueInvoiceHandler implements Handles<IssueInvoice, UUID> {

    static final String INV = "INV";
    static final String AUDIT_ISSUED = "INVOICE_ISSUED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final TradingSeries series;
    private final GrnReads grns;
    private final PartyQueries parties;
    private final RelationshipQueries relationships;
    private final CatalogueQueries catalogue;
    private final TaxRates taxRates;
    private final PostingMapper postings;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    IssueInvoiceHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            TradingSeries series,
            GrnReads grns,
            PartyQueries parties,
            RelationshipQueries relationships,
            CatalogueQueries catalogue,
            TaxRates taxRates,
            PostingMapper postings,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.series = series;
        this.grns = grns;
        this.parties = parties;
        this.relationships = relationships;
        this.catalogue = catalogue;
        this.taxRates = taxRates;
        this.postings = postings;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(IssueInvoice command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID seller = scope.entityId();
        if (command.grnIds() == null || command.grnIds().isEmpty()) {
            throw new ProblemException("m4.invoice.grns_required");
        }
        Set<UUID> grnIds = new LinkedHashSet<>(command.grnIds());
        List<Grn> received = new ArrayList<>();
        UUID buyer = null;
        UUID relationshipId = null;
        for (UUID grnId : grnIds) {
            Grn grn = grns.grn(grnId)
                    .filter(found -> GrnReads.GRN.equals(found.header().docTypeCode()))
                    .orElseThrow(() -> new ProblemException("m4.invoice.grn_unknown", Map.of("grnId", grnId)));
            if (!seller.equals(grn.sellerEntityId())) {
                throw new ProblemException("m4.invoice.grn_unknown", Map.of("grnId", grnId));
            }
            if (!GrnReads.CONFIRMED.equals(grn.header().status())) {
                throw new ProblemException("m4.invoice.grn_not_confirmed", Map.of("grnId", grnId));
            }
            if (buyer == null) {
                buyer = grn.receiverEntityId();
                relationshipId = grn.relationshipId();
            } else if (!buyer.equals(grn.receiverEntityId())
                    || !java.util.Objects.equals(relationshipId, grn.relationshipId())) {
                throw new ProblemException("m4.invoice.one_buyer");
            }
            jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "invoice-" + grnId);
            received.add(grn);
        }
        Integer invoiced = jdbc.queryForObject(
                "select count(*) from trading.doc_invoice where grn_document_ids && ?::uuid[]", Integer.class, (Object)
                        grnIds.toArray(new UUID[0]));
        if (invoiced != null && invoiced > 0) {
            throw new ProblemException("m4.invoice.grn_invoiced");
        }
        String sellerVat = parties.getEntity(seller, scope)
                .map(EntityView::vatRegistrationNo)
                .filter(vat -> !vat.isBlank())
                .orElseThrow(() -> new ProblemException("m4.invoice.seller_vat_missing"));
        // The buyer's VAT number is not readable by the seller (M1 gives a counterparty its names
        // only, entity_party_directory); recorded when visible, empty otherwise (M4-08 deviation).
        UUID buyerId = buyer;
        String buyerVat = parties.getEntity(buyerId, scope)
                .map(EntityView::vatRegistrationNo)
                .orElse("");
        LocalDate taxPoint = clock.today();
        RelationshipView relationship = relationshipId == null
                ? null
                : relationships.getRelationship(relationshipId, scope).orElse(null);
        int terms =
                relationship == null || relationship.paymentTermsDays() == null ? 0 : relationship.paymentTermsDays();
        LocalDate dueDate = taxPoint.plusDays(terms);

        UUID invoiceId = Ids.next();
        List<DocumentLineRecord> lines = new ArrayList<>();
        int lineNo = 0;
        for (Grn grn : received) {
            for (GrnLine line : grn.lines()) {
                if (line.receivedQty().signum() <= 0) {
                    continue;
                }
                SkuView sku = catalogue
                        .getSku(line.skuId(), scope)
                        .orElseThrow(
                                () -> new ProblemException("m4.order.sku_not_found", Map.of("skuId", line.skuId())));
                BigDecimal rate = taxRates.ratePercent(sku.taxCategoryId(), taxPoint, scope)
                        .orElseThrow(() ->
                                new ProblemException("m4.invoice.tax_rate_missing", Map.of("skuId", line.skuId())));
                BigDecimal price = line.unitCost() == null ? BigDecimal.ZERO : line.unitCost();
                BigDecimal net = price.multiply(line.receivedQty()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal tax = net.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                lineNo++;
                lines.add(TradingDocuments.line(
                        Ids.next(),
                        invoiceId,
                        lineNo,
                        line.skuId(),
                        line.batchId(),
                        line.uomCode(),
                        line.receivedQty(),
                        price,
                        rate,
                        tax,
                        net,
                        null,
                        line.lineId()));
            }
        }
        if (lines.isEmpty()) {
            throw new ProblemException("m4.invoice.nothing_received");
        }

        documents.save(TradingDocuments.draft(invoiceId, INV, seller, buyerId, null, scope.userId(), null, null));
        documents.saveLines(invoiceId, lines);
        series.ensureEntitySeries(INV, scope);
        DocumentRecord issued = issuance.issue(
                documents.findByIdForUpdate(invoiceId).orElseThrow(), documents.findLines(invoiceId), scope);
        jdbc.update(
                """
                insert into trading.doc_invoice (document_id, relationship_id, seller_entity_id, buyer_entity_id,
                    grn_document_ids, delivery_document_id, seller_vat_no, buyer_vat_no, tax_point_date, due_date)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                invoiceId,
                relationshipId,
                seller,
                buyerId,
                grnIds.toArray(new UUID[0]),
                received.get(0).deliveryDocumentId(),
                sellerVat,
                buyerVat,
                taxPoint,
                dueDate);

        List<Posting> journal =
                postings.postings(INV, "GOODS", "SELLER", Map.of("net", issued.netAmount(), "tax", issued.taxAmount()));

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", issued.status());
        after.put("docNumber", issued.docNumberDisplay());
        after.put("grnIds", List.copyOf(grnIds));
        after.put("netAmount", issued.netAmount());
        after.put("taxAmount", issued.taxAmount());
        after.put("grossAmount", issued.grossAmount());
        audit.record(AUDIT_ISSUED, Subject.of("invoice", invoiceId), null, after, scope);

        events.publish(new InvoiceIssued(
                invoiceId,
                issued.docNumberDisplay(),
                relationshipId,
                seller,
                buyerId,
                sellerVat,
                buyerVat,
                List.copyOf(grnIds),
                taxPoint,
                dueDate,
                issued.netAmount(),
                issued.taxAmount(),
                issued.grossAmount(),
                issued.contentHash()));
        events.publish(new JournalPostingsReady(invoiceId, INV, issued.docNumberDisplay(), seller, journal));
        return invoiceId;
    }
}
