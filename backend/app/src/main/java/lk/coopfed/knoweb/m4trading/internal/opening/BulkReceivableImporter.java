package lk.coopfed.knoweb.m4trading.internal.opening;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import lk.coopfed.knoweb.kernel.api.PolicyClass;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.Scope;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m1party.query.EntityView;
import lk.coopfed.knoweb.m1party.query.PartyQueries;
import lk.coopfed.knoweb.m1party.query.RelationshipQueries;
import lk.coopfed.knoweb.m4trading.api.BulkImportReceivables;
import lk.coopfed.knoweb.m4trading.api.BulkReceivableReport;
import lk.coopfed.knoweb.m4trading.api.OpeningReceivableLoaded;
import lk.coopfed.knoweb.m4trading.api.ReceivableOpeningRow;
import lk.coopfed.knoweb.m4trading.api.ReceivablesBulkImported;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk importer for inter-entity receivable opening balances (CAT-12).
 *
 * <p>Loads opening receivable balances as authorized two-party signed documents (INV).
 * Each balance creates a kernel document, trading invoice, and an opening receivable record
 * carrying both the seller's and the buyer's signatures.
 */
@Service
@CommandHandler(permission = "bil.invoice.issue")
public class BulkReceivableImporter implements Handles<BulkImportReceivables, BulkReceivableReport> {

    public static final String INV = "INV";
    public static final String AUDIT_RECEIVABLE_IMPORTED = "RECEIVABLE_OPENING_IMPORTED";
    public static final String AUDIT_BULK_IMPORTED = "RECEIVABLES_BULK_IMPORTED";

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final TradingSeries series;
    private final PartyQueries parties;
    private final RelationshipQueries relationships;
    private final AuditFacade audit;
    private final EventPublisher events;
    private final TradingClock clock;

    public BulkReceivableImporter(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            TradingSeries series,
            PartyQueries parties,
            RelationshipQueries relationships,
            AuditFacade audit,
            EventPublisher events,
            TradingClock clock) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.series = series;
        this.parties = parties;
        this.relationships = relationships;
        this.audit = audit;
        this.events = events;
        this.clock = clock;
    }

    @Override
    @Transactional
    public BulkReceivableReport handle(BulkImportReceivables command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);

        UUID effectiveSeller = command.sellerEntityId() != null ? command.sellerEntityId() : scope.entityId();
        if (!effectiveSeller.equals(scope.entityId()) && !isFederation(scope)) {
            throw new ProblemException("scope.invalid");
        }

        List<ReceivableOpeningRow> rows = command.rows();
        if (command.sheet() != null && !command.sheet().isBlank()) {
            rows = BulkReceivableCsvParser.parse(command.sheet());
        }
        if (rows == null || rows.isEmpty()) {
            throw new ProblemException("m4.receivable.lines_required");
        }

        ScopeContext sellerScope = ownerScope(scope, effectiveSeller);
        series.ensureEntitySeries(INV, sellerScope);

        UUID importId = Ids.next();
        List<UUID> documentIds = new ArrayList<>();
        BigDecimal totalAmount = BigDecimal.ZERO;

        for (int i = 0; i < rows.size(); i++) {
            ReceivableOpeningRow row = rows.get(i);
            requireRow(row, i + 1);

            UUID sellerId = row.sellerEntityId();
            if (sellerId == null
                    && row.sellerCode() != null
                    && !row.sellerCode().isBlank()) {
                sellerId = resolveEntity(row.sellerCode().strip());
            }
            if (sellerId == null) {
                sellerId = effectiveSeller;
            }

            UUID buyerId = row.buyerEntityId();
            if (buyerId == null && row.buyerCode() != null && !row.buyerCode().isBlank()) {
                buyerId = resolveEntity(row.buyerCode().strip());
            }
            if (buyerId == null) {
                throw new ProblemException("m4.receivable.buyer_not_found", Map.of("row", i + 1));
            }
            if (sellerId.equals(buyerId)) {
                throw new ProblemException("m4.receivable.same_entity", Map.of("row", i + 1));
            }

            BigDecimal amount = row.amount();
            totalAmount = totalAmount.add(amount);

            LocalDate taxPoint = row.asOfDate() != null ? row.asOfDate() : clock.today();
            LocalDate dueDate = row.dueDate() != null ? row.dueDate() : taxPoint.plusDays(30);

            UUID relationshipId = findRelationship(sellerId, buyerId, taxPoint, scope);
            if (relationshipId == null) {
                throw new ProblemException("m4.receivable.no_relationship", Map.of("row", i + 1, "buyerId", buyerId));
            }

            UUID sellerSignedBy = resolveSigner(
                    sellerId, row.sellerSignedBy(), row.sellerSigner(), scope.userId(), command.simulateSignatures());
            UUID buyerSignedBy =
                    resolveSigner(buyerId, row.buyerSignedBy(), row.buyerSigner(), null, command.simulateSignatures());

            if (sellerSignedBy == null || buyerSignedBy == null) {
                throw new ProblemException("m4.receivable.buyer_signature_required", Map.of("row", i + 1));
            }

            Instant sellerSignedAt = clock.now();
            Instant buyerSignedAt = clock.now();

            String sellerVat = parties.getEntity(sellerId, sellerScope)
                    .map(EntityView::vatRegistrationNo)
                    .orElse("");
            String buyerVat = parties.getEntity(buyerId, sellerScope)
                    .map(EntityView::vatRegistrationNo)
                    .orElse("");

            UUID invoiceId = Ids.next();
            UUID openingReceivableId = Ids.next();

            DocumentRecord draft = TradingDocuments.draft(
                    invoiceId,
                    INV,
                    sellerId,
                    buyerId,
                    null,
                    sellerSignedBy,
                    null,
                    row.notes() != null
                            ? row.notes()
                            : (row.referenceNo() != null ? row.referenceNo() : "Opening receivable"));

            DocumentLineRecord line = TradingDocuments.line(
                    Ids.next(),
                    invoiceId,
                    1,
                    null,
                    null,
                    "EA",
                    BigDecimal.ONE,
                    amount,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    amount,
                    null,
                    null);

            documents.save(draft);
            documents.saveLines(invoiceId, List.of(line));

            DocumentRecord issued =
                    issuance.issue(documents.findByIdForUpdate(invoiceId).orElseThrow(), List.of(line), sellerScope);

            jdbc.update(
                    """
                    insert into trading.doc_invoice
                        (document_id, relationship_id, seller_entity_id, buyer_entity_id, grn_document_ids,
                         delivery_document_id, seller_vat_no, buyer_vat_no, tax_point_date, due_date,
                         settled_amount, credited_amount, debited_amount, e_invoice_status)
                    values (?, ?, ?, ?, '{}', null, ?, ?, ?, ?, 0, 0, 0, 'NOT_SUBMITTED')
                    """,
                    invoiceId,
                    relationshipId,
                    sellerId,
                    buyerId,
                    sellerVat,
                    buyerVat,
                    taxPoint,
                    dueDate);

            jdbc.update(
                    """
                    insert into trading.opening_receivable
                        (opening_receivable_id, document_id, relationship_id, seller_entity_id, buyer_entity_id,
                         amount, reference_no, seller_signed_by, seller_signed_at, buyer_signed_by, buyer_signed_at,
                         notes, created_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    openingReceivableId,
                    invoiceId,
                    relationshipId,
                    sellerId,
                    buyerId,
                    amount,
                    row.referenceNo(),
                    sellerSignedBy,
                    sellerSignedAt,
                    buyerSignedBy,
                    buyerSignedAt,
                    row.notes(),
                    clock.now());

            documentIds.add(invoiceId);

            audit.record(
                    AUDIT_RECEIVABLE_IMPORTED,
                    Subject.of("opening_receivable", openingReceivableId),
                    null,
                    Map.of(
                            "documentId", invoiceId,
                            "sellerEntityId", sellerId,
                            "buyerEntityId", buyerId,
                            "amount", amount,
                            "sellerSignedBy", sellerSignedBy,
                            "buyerSignedBy", buyerSignedBy),
                    scope);

            events.publish(new OpeningReceivableLoaded(
                    openingReceivableId, invoiceId, sellerId, buyerId, amount, sellerSignedBy, buyerSignedBy));
        }

        audit.record(
                AUDIT_BULK_IMPORTED,
                Subject.of("entity", effectiveSeller),
                null,
                Map.of(
                        "importId",
                        importId,
                        "totalRows",
                        rows.size(),
                        "importedCount",
                        documentIds.size(),
                        "totalAmount",
                        totalAmount),
                scope);

        events.publish(new ReceivablesBulkImported(
                importId, effectiveSeller, rows.size(), documentIds.size(), totalAmount, documentIds));

        return new BulkReceivableReport(
                importId, BulkReceivableReport.IMPORTED, rows.size(), documentIds.size(), documentIds, List.of());
    }

    private static void requireRow(ReceivableOpeningRow row, int lineNo) {
        if (row == null) {
            throw new ProblemException("m4.receivable.lines_required");
        }
        if (row.buyerEntityId() == null
                && (row.buyerCode() == null || row.buyerCode().isBlank())) {
            throw new ProblemException("m4.receivable.buyer_not_found", Map.of("row", lineNo));
        }
        if (row.amount() == null
                || row.amount().signum() <= 0
                || row.amount().stripTrailingZeros().scale() > 2) {
            throw new ProblemException("m4.receivable.amount_invalid", Map.of("row", lineNo));
        }
    }

    private UUID resolveEntity(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException ignored) {
        }
        List<UUID> ids = jdbc.query(
                "select entity_id from party.entity where entity_code = ? or legal_name_en = ? limit 1",
                (rs, rowNum) -> rs.getObject("entity_id", UUID.class),
                text,
                text);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private UUID findRelationship(UUID sellerId, UUID buyerId, LocalDate onDate, ScopeContext scope) {
        var opt = relationships.settlementRelationship(sellerId, buyerId, onDate, scope);
        if (opt.isPresent()) {
            return opt.get().relationshipId();
        }
        opt = relationships.lookupRelationship(sellerId, buyerId, onDate, scope);
        if (opt.isPresent()) {
            return opt.get().relationshipId();
        }
        List<UUID> ids = jdbc.query(
                """
                select relationship_id from party.relationship
                 where seller_entity_id = ? and buyer_entity_id = ?
                 order by created_at desc limit 1
                """,
                (rs, rowNum) -> rs.getObject("relationship_id", UUID.class),
                sellerId,
                buyerId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private UUID resolveSigner(UUID entityId, UUID directId, String username, UUID fallbackId, boolean simulate) {
        if (directId != null) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from security.app_user where user_id = ? and home_entity_id = ?",
                    Integer.class,
                    directId,
                    entityId);
            if (count != null && count > 0) {
                return directId;
            }
        }
        if (username != null && !username.isBlank()) {
            List<UUID> ids = jdbc.query(
                    "select user_id from security.app_user where (username = ? or display_name = ?) and home_entity_id = ? limit 1",
                    (rs, rowNum) -> rs.getObject("user_id", UUID.class),
                    username.strip(),
                    username.strip(),
                    entityId);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }
        if (fallbackId != null) {
            Integer count = jdbc.queryForObject(
                    "select count(*) from security.app_user where user_id = ? and home_entity_id = ?",
                    Integer.class,
                    fallbackId,
                    entityId);
            if (count != null && count > 0) {
                return fallbackId;
            }
        }
        if (simulate) {
            List<UUID> ids = jdbc.query(
                    "select user_id from security.app_user where home_entity_id = ? and status = 'ACTIVE' order by user_id limit 1",
                    (rs, rowNum) -> rs.getObject("user_id", UUID.class),
                    entityId);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
        }
        return null;
    }

    private static ScopeContext ownerScope(ScopeContext parent, UUID ownerEntityId) {
        Scope owner = new Scope(ownerEntityId, null);
        return new ScopeContext(
                parent.userId(),
                parent.deviceId(),
                parent.homeEntityId(),
                List.of(owner),
                owner,
                PolicyClass.OWN,
                parent.grantedEntities(),
                parent.mfaAt(),
                parent.locale(),
                parent.correlationId());
    }

    private boolean isFederation(ScopeContext scope) {
        try {
            return Boolean.TRUE.equals(
                    jdbc.queryForObject("select kernel.scope_entity() = kernel.system_entity()", Boolean.class));
        } catch (Exception e) {
            return false;
        }
    }
}
