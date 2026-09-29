package lk.coopfed.knoweb.m4trading.internal.claim;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.CommandHandler;
import lk.coopfed.knoweb.kernel.api.ConfigRegistry;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentIssuance;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.EventPublisher;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.Ids;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import lk.coopfed.knoweb.m4trading.api.ClaimLine;
import lk.coopfed.knoweb.m4trading.api.ClaimRaised;
import lk.coopfed.knoweb.m4trading.api.RaiseClaim;
import lk.coopfed.knoweb.m4trading.internal.document.TradingClock;
import lk.coopfed.knoweb.m4trading.internal.document.TradingDocuments;
import lk.coopfed.knoweb.m4trading.internal.document.TradingGuards;
import lk.coopfed.knoweb.m4trading.internal.document.TradingSeries;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads;
import lk.coopfed.knoweb.m4trading.internal.grn.GrnReads.GrnLine;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * RaiseClaim (24A section 6; doc 24 sections 3.5, 4.5 and flow 6.3): the buyer claims against the
 * seller for goods of a confirmed GRN found later to be damaged, expired, the wrong goods or of poor
 * quality.
 *
 * <p>Guards, in order: the buyer's entity-wide OWN scope; a GRN and a known kind ({@code
 * m4.claim.kind_invalid}); the caller's own GRN ({@code m4.claim.grn_not_found}), CONFIRMED ({@code
 * m4.claim.grn_not_confirmed}) and received from a seller ({@code m4.claim.no_seller}: a local supply
 * has no counterparty in M4); within {@code trading.claim_window_days} of the confirmation ({@code
 * m4.claim.window_closed}); lines ({@code m4.claim.lines_required}), each a line of the GRN ({@code
 * m4.claim.line_unknown}) once ({@code m4.claim.line_duplicate}), a quantity above zero with at
 * most three decimals ({@code m4.claim.qty_invalid}) and at most what was received less what
 * earlier claims not rejected hold ({@code m4.claim.exceeds_received}, under an advisory lock per
 * GRN).
 *
 * <p>Mutation: the CLM document from the buyer's ENTITY series (24B) at the GRN's location, ISSUED
 * then RAISED; a DISPUTES link to the GRN; {@code doc_claim} and {@code doc_claim_line}. Audit
 * CLAIM_RAISED (REVIEW: the seller must act); event claim.raised.v1.
 */
@Service
@CommandHandler(permission = "del.claim.raise")
public class RaiseClaimHandler implements Handles<RaiseClaim, UUID> {

    static final String AUDIT_RAISED = "CLAIM_RAISED";
    static final String WINDOW_DAYS = "trading.claim_window_days";
    static final Set<String> KINDS = Set.of("DAMAGED", "EXPIRED_ON_ARRIVAL", "WRONG_GOODS", "QUALITY");

    private final JdbcTemplate jdbc;
    private final DocumentBaseRepository documents;
    private final DocumentIssuance issuance;
    private final DocumentLinks links;
    private final TradingSeries series;
    private final GrnReads grns;
    private final ClaimReads claims;
    private final ConfigRegistry config;
    private final TradingClock clock;
    private final AuditFacade audit;
    private final EventPublisher events;

    @SuppressWarnings("java:S107") // the collaborators of one handler specification
    RaiseClaimHandler(
            JdbcTemplate jdbc,
            DocumentBaseRepository documents,
            DocumentIssuance issuance,
            DocumentLinks links,
            TradingSeries series,
            GrnReads grns,
            ClaimReads claims,
            ConfigRegistry config,
            TradingClock clock,
            AuditFacade audit,
            EventPublisher events) {
        this.jdbc = jdbc;
        this.documents = documents;
        this.issuance = issuance;
        this.links = links;
        this.series = series;
        this.grns = grns;
        this.claims = claims;
        this.config = config;
        this.clock = clock;
        this.audit = audit;
        this.events = events;
    }

    @Override
    @Transactional
    public UUID handle(RaiseClaim command, ScopeContext scope) {
        if (command == null) {
            throw new ProblemException("request.invalid");
        }
        TradingGuards.requireEntityScope(scope);
        UUID buyer = scope.entityId();
        UUID grnId = TradingGuards.required(command.grnId(), "grnId");
        String kind = TradingGuards.required(command.kind(), "kind");
        if (!KINDS.contains(kind)) {
            throw new ProblemException("m4.claim.kind_invalid", Map.of("kind", kind));
        }
        GrnReads.Grn grn = grns.grn(grnId)
                .filter(found -> buyer.equals(found.receiverEntityId()))
                .orElseThrow(() -> new ProblemException("m4.claim.grn_not_found"));
        if (!GrnReads.CONFIRMED.equals(grn.header().status()) || grn.confirmedAt() == null) {
            throw new ProblemException("m4.claim.grn_not_confirmed");
        }
        UUID seller = grn.sellerEntityId();
        if (seller == null) {
            throw new ProblemException("m4.claim.no_seller");
        }
        Instant now = clock.now();
        Instant windowEndsAt = grn.confirmedAt().plus(Duration.ofDays(config.getInt(WINDOW_DAYS, scope, 14)));
        if (now.isAfter(windowEndsAt)) {
            throw new ProblemException("m4.claim.window_closed", Map.of("windowEndsAt", windowEndsAt.toString()));
        }
        if (command.lines().isEmpty()) {
            throw new ProblemException("m4.claim.lines_required");
        }
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?::text))", "claim-grn-" + grnId);
        Map<UUID, GrnLine> grnLines = new LinkedHashMap<>();
        for (GrnLine line : grn.lines()) {
            grnLines.put(line.lineId(), line);
        }
        Set<UUID> seen = new HashSet<>();
        UUID claimId = Ids.next();
        List<ClaimLine> lines = new ArrayList<>();
        for (RaiseClaim.Line want : command.lines()) {
            GrnLine line = want == null ? null : grnLines.get(want.grnLineId());
            if (line == null) {
                throw new ProblemException("m4.claim.line_unknown");
            }
            if (!seen.add(line.lineId())) {
                throw new ProblemException("m4.claim.line_duplicate", Map.of("skuId", line.skuId()));
            }
            BigDecimal qty = want.qty();
            if (qty == null || qty.signum() <= 0 || qty.stripTrailingZeros().scale() > 3) {
                throw new ProblemException("m4.claim.qty_invalid", Map.of("skuId", line.skuId()));
            }
            BigDecimal received = line.receivedQty() == null ? BigDecimal.ZERO : line.receivedQty();
            BigDecimal open = received.subtract(claims.claimedBefore(line.lineId()));
            if (qty.compareTo(open) > 0) {
                throw new ProblemException(
                        "m4.claim.exceeds_received", Map.of("skuId", line.skuId(), "open", open.toPlainString()));
            }
            lines.add(new ClaimLine(Ids.next(), line.lineId(), line.skuId(), line.batchId(), line.uomCode(), qty));
        }

        String note = command.note() == null || command.note().isBlank()
                ? null
                : command.note().strip();
        documents.save(TradingDocuments.draft(
                claimId, ClaimReads.CLM, buyer, seller, grn.receiverLocationId(), scope.userId(), grnId, note));
        List<DocumentLineRecord> kernelLines = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ClaimLine line = lines.get(i);
            kernelLines.add(TradingDocuments.line(
                    line.claimLineId(),
                    claimId,
                    i + 1,
                    line.skuId(),
                    line.batchId(),
                    line.uomCode(),
                    line.qty(),
                    null,
                    null,
                    null,
                    null,
                    null,
                    line.grnLineId()));
        }
        documents.saveLines(claimId, kernelLines);
        series.ensureEntitySeries(ClaimReads.CLM, scope);
        DocumentRecord issued =
                issuance.issue(documents.findByIdForUpdate(claimId).orElseThrow(), documents.findLines(claimId), scope);
        documents.addStateTransition(
                clock.transition(claimId, TradingDocuments.ISSUED, ClaimReads.RAISED, scope.userId(), kind, null),
                scope);
        links.link(claimId, grnId, LinkType.DISPUTES, null, scope);
        jdbc.update(
                "insert into trading.doc_claim (document_id, grn_document_id, kind, return_requested, note, window_ends_at)"
                        + " values (?, ?, ?, ?, ?, ?)",
                claimId,
                grnId,
                kind,
                command.returnRequested(),
                note,
                Timestamp.from(windowEndsAt));
        for (int i = 0; i < lines.size(); i++) {
            ClaimLine line = lines.get(i);
            jdbc.update(
                    """
                    insert into trading.doc_claim_line (line_id, document_id, line_no, grn_line_id, sku_id, batch_id,
                        uom_code, claimed_qty)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    line.claimLineId(),
                    claimId,
                    i + 1,
                    line.grnLineId(),
                    line.skuId(),
                    line.batchId(),
                    line.uomCode(),
                    line.qty());
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", ClaimReads.RAISED);
        after.put("docNumber", issued.docNumberDisplay());
        after.put("grnId", grnId);
        after.put("kind", kind);
        after.put("returnRequested", command.returnRequested());
        after.put("lines", lines.size());
        audit.record(AUDIT_RAISED, Subject.of("claim", claimId), null, after, scope);
        events.publish(new ClaimRaised(
                claimId,
                issued.docNumberDisplay(),
                grnId,
                buyer,
                seller,
                grn.receiverLocationId(),
                kind,
                command.returnRequested(),
                windowEndsAt,
                List.copyOf(lines)));
        return claimId;
    }
}
