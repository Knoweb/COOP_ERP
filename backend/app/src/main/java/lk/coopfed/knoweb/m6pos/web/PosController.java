package lk.coopfed.knoweb.m6pos.web;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.m6pos.query.PosQueries;
import lk.coopfed.knoweb.m6pos.query.PosQueries.ReceiptFilter;
import lk.coopfed.knoweb.m6pos.query.PosQueries.ReceiptView;
import lk.coopfed.knoweb.m6pos.query.PosQueries.SessionFilter;
import lk.coopfed.knoweb.m6pos.query.PosQueries.SessionView;
import lk.coopfed.knoweb.m6pos.web.generated.PosApi;
import lk.coopfed.knoweb.m6pos.web.generated.ReceiptLineResponse;
import lk.coopfed.knoweb.m6pos.web.generated.ReceiptPage;
import lk.coopfed.knoweb.m6pos.web.generated.ReceiptResponse;
import lk.coopfed.knoweb.m6pos.web.generated.ReceiptTenderResponse;
import lk.coopfed.knoweb.m6pos.web.generated.SessionPage;
import lk.coopfed.knoweb.m6pos.web.generated.SessionResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The reads of M6 (26A section 10, "web/ PosController: ... sessions, receipts"): a shop's
 * receipts and sessions one business day a page at a time (today in the business time zone when
 * no day is asked for), and one receipt or one session (wave 2, M6-08).
 */
@RestController
class PosController implements PosApi {

    private final PosQueries queries;
    private final CurrentScope currentScope;
    private final Clock clock;
    private final ZoneId businessZone;

    PosController(
            PosQueries queries,
            CurrentScope currentScope,
            Clock clock,
            @Value("${coop-erp.business-timezone}") String businessZone) {
        this.queries = queries;
        this.currentScope = currentScope;
        this.clock = clock;
        this.businessZone = ZoneId.of(businessZone);
    }

    @Override
    public ResponseEntity<ReceiptPage> listReceipts(
            UUID locationId, LocalDate businessDate, Boolean flagged, String cursor, Integer limit) {
        PosQueries.Page<ReceiptView> page = queries.receipts(
                new ReceiptFilter(locationId, dayOrToday(businessDate), Boolean.TRUE.equals(flagged), cursor, limit),
                currentScope.get());
        return ResponseEntity.ok(new ReceiptPage(
                        page.items().stream().map(PosController::toResponse).toList())
                .nextCursor(page.nextCursor()));
    }

    @Override
    public ResponseEntity<ReceiptResponse> getPosReceipt(UUID documentId) {
        return queries.receipt(documentId, currentScope.get())
                .map(r -> ResponseEntity.ok(toResponse(r)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @Override
    public ResponseEntity<SessionPage> listSessions(
            UUID locationId, LocalDate businessDate, String cursor, Integer limit) {
        PosQueries.Page<SessionView> page = queries.sessions(
                new SessionFilter(locationId, dayOrToday(businessDate), cursor, limit), currentScope.get());
        return ResponseEntity.ok(new SessionPage(
                        page.items().stream().map(PosController::toResponse).toList())
                .nextCursor(page.nextCursor()));
    }

    @Override
    public ResponseEntity<SessionResponse> getTillSession(UUID sessionId) {
        return queries.session(sessionId, currentScope.get())
                .map(s -> ResponseEntity.ok(toResponse(s)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private LocalDate dayOrToday(LocalDate businessDate) {
        return businessDate != null ? businessDate : LocalDate.now(clock.withZone(businessZone));
    }

    private static ReceiptResponse toResponse(ReceiptView r) {
        return new ReceiptResponse(
                        r.documentId(),
                        r.locationId(),
                        r.issuedAt(),
                        r.flags(),
                        r.lines().stream()
                                .map(l -> new ReceiptLineResponse(l.lineNo(), l.qty())
                                        .skuId(l.skuId())
                                        .batchId(l.batchId())
                                        .unitPrice(l.unitPrice())
                                        .lineTotal(l.lineTotal()))
                                .toList(),
                        r.tenders().stream()
                                .map(x -> new ReceiptTenderResponse(x.seq(), x.kind(), x.amount()))
                                .toList())
                .tillPositionId(r.tillPositionId())
                .netAmount(r.netAmount())
                .taxAmount(r.taxAmount())
                .deviceId(r.deviceId())
                .sessionId(r.sessionId())
                .docNumberDisplay(r.docNumberDisplay())
                .businessDate(r.businessDate())
                .grossAmount(r.grossAmount());
    }

    private static SessionResponse toResponse(SessionView s) {
        return new SessionResponse(
                        s.sessionId(),
                        s.locationId(),
                        s.closedAt() == null ? SessionResponse.StatusEnum.OPEN : SessionResponse.StatusEnum.CLOSED)
                .openedAt(s.openedAt())
                .tillPositionId(s.tillPositionId())
                .businessDate(s.businessDate())
                .floatAmount(s.floatAmount())
                .closedAt(s.closedAt())
                .countedCash(s.countedCash())
                .expectedCash(s.expectedCash())
                .variance(s.variance());
    }
}
