package lk.coopfed.knoweb.m6pos.web;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.m6pos.query.PosQueries;
import lk.coopfed.knoweb.m6pos.web.generated.PosApi;
import lk.coopfed.knoweb.m6pos.web.generated.ReceiptLineResponse;
import lk.coopfed.knoweb.m6pos.web.generated.ReceiptResponse;
import lk.coopfed.knoweb.m6pos.web.generated.ReceiptTenderResponse;
import lk.coopfed.knoweb.m6pos.web.generated.SessionResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The reads of M6 for the demo (26A section 10, "web/ PosController: ... sessions, receipts"). */
@RestController
class PosController implements PosApi {

    private final PosQueries queries;
    private final CurrentScope currentScope;

    PosController(PosQueries queries, CurrentScope currentScope) {
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<List<ReceiptResponse>> listReceipts(UUID locationId) {
        return ResponseEntity.ok(queries.receipts(locationId, currentScope.get()).stream()
                .map(r -> new ReceiptResponse(
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
                        .grossAmount(r.grossAmount()))
                .toList());
    }

    @Override
    public ResponseEntity<List<SessionResponse>> listSessions(UUID locationId) {
        return ResponseEntity.ok(queries.sessions(locationId, currentScope.get()).stream()
                .map(s -> new SessionResponse(
                                s.sessionId(),
                                s.locationId(),
                                s.openedAt(),
                                s.closedAt() == null
                                        ? SessionResponse.StatusEnum.OPEN
                                        : SessionResponse.StatusEnum.CLOSED)
                        .tillPositionId(s.tillPositionId())
                        .businessDate(s.businessDate())
                        .floatAmount(s.floatAmount())
                        .closedAt(s.closedAt())
                        .countedCash(s.countedCash())
                        .expectedCash(s.expectedCash())
                        .variance(s.variance()))
                .toList());
    }
}
