package lk.coopfed.knoweb.m4trading.internal.queries;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.Attachments;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.ClaimLine;
import lk.coopfed.knoweb.m4trading.internal.claim.ClaimReads;
import lk.coopfed.knoweb.m4trading.query.ClaimQueries;
import lk.coopfed.knoweb.m4trading.query.ClaimView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The claims, from the document base, {@code trading.doc_claim}, the seller's decision rows and
 * the buyer's return row (V0008); unpaged for the demo. The buyer owns the claim and the seller is
 * its counterparty, so both read it through document_read, and each other's rows through
 * party_read.
 */
@Service
class ClaimQueriesImpl implements ClaimQueries {

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final ClaimReads claims;
    private final Attachments attachments;

    ClaimQueriesImpl(JdbcTemplate jdbc, DocumentBaseRepository documents, ClaimReads claims, Attachments attachments) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.claims = claims;
        this.attachments = attachments;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ClaimView> getClaim(UUID claimId, ScopeContext scope) {
        Optional<ClaimReads.Claim> found = claims.claim(claimId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ClaimReads.Claim claim = found.get();
        Optional<ClaimReads.Decision> decision = claims.decision(claimId);
        UUID invoiceId = jdbc
                .queryForList(
                        "select document_id from trading.doc_invoice where ? = any (grn_document_ids)",
                        UUID.class,
                        claim.grnId())
                .stream()
                .findFirst()
                .orElse(null);
        Map<UUID, BigDecimal> priceByGrnLine = new HashMap<>();
        if (invoiceId != null) {
            for (DocumentLineRecord line : documents.findLines(invoiceId)) {
                if (line.referenceLineId() != null) {
                    priceByGrnLine.put(line.referenceLineId(), line.unitPrice());
                }
            }
        }
        List<ClaimView.Line> lines = new ArrayList<>();
        for (ClaimLine line : claim.lines()) {
            lines.add(new ClaimView.Line(
                    line.claimLineId(),
                    line.grnLineId(),
                    line.skuId(),
                    line.batchId(),
                    line.uomCode(),
                    line.qty(),
                    decision.map(d -> d.approvedByLine().get(line.claimLineId()))
                            .orElse(null),
                    priceByGrnLine.get(line.grnLineId())));
        }
        List<ClaimView.Photo> photos = claim.photoIds().stream()
                .map(id -> new ClaimView.Photo(id, attachments.status(id).orElse("PENDING")))
                .toList();
        UUID creditNoteId = decision.map(ClaimReads.Decision::creditNoteId).orElse(null);
        DocumentRecord header = claim.header();
        return Optional.of(new ClaimView(
                header.id(),
                header.docNumberDisplay(),
                decision.map(ClaimReads.Decision::decision).orElse(ClaimView.RAISED),
                claim.kind(),
                header.ownerEntityId(),
                header.counterpartyEntityId(),
                header.locationId(),
                claim.grnId(),
                documents
                        .findById(claim.grnId())
                        .map(DocumentRecord::docNumberDisplay)
                        .orElse(null),
                header.issuedAt(),
                claim.windowEndsAt(),
                claim.returnRequested(),
                claim.note(),
                invoiceId,
                decision.map(ClaimReads.Decision::findings).orElse(null),
                decision.map(ClaimReads.Decision::reason).orElse(null),
                decision.map(ClaimReads.Decision::returnRequired).orElse(false),
                creditNoteId,
                creditNoteId == null
                        ? null
                        : documents
                                .findById(creditNoteId)
                                .map(DocumentRecord::docNumberDisplay)
                                .orElse(null),
                decision.map(ClaimReads.Decision::decidedBy).orElse(null),
                decision.map(ClaimReads.Decision::decidedAt).orElse(null),
                claims.returnedAt(claimId).orElse(null),
                photos,
                List.copyOf(lines)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClaimView> listClaims(OrderQueries.Role role, ScopeContext scope) {
        if (scope == null || scope.entityId() == null) {
            return List.of();
        }
        // The buyer (the GRN's receiver) raised the claim with the GRN's seller.
        String column = role == OrderQueries.Role.SELLER ? "g.seller_entity_id" : "g.receiver_entity_id";
        List<ClaimView> views = new ArrayList<>();
        for (UUID id : jdbc.queryForList(
                "select c.document_id from trading.doc_claim c"
                        + " join trading.doc_grn g on g.document_id = c.grn_document_id"
                        + " where " + column + " = ? order by c.document_id desc",
                UUID.class,
                scope.entityId())) {
            getClaim(id, scope).ifPresent(views::add);
        }
        return views;
    }
}
