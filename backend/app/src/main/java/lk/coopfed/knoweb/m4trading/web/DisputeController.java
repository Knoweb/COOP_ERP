package lk.coopfed.knoweb.m4trading.web;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.SettleDiscrepancy;
import lk.coopfed.knoweb.m4trading.internal.invoice.SettleDiscrepancyHandler;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyQueries;
import lk.coopfed.knoweb.m4trading.query.DiscrepancyView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.web.generated.DiscrepancyLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.DiscrepancyResponse;
import lk.coopfed.knoweb.m4trading.web.generated.DisputeApi;
import lk.coopfed.knoweb.m4trading.web.generated.SettleDiscrepancyRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The discrepancy reads of 24A section 5 (DisputeController, demo scope). The seller settles a
 * discrepancy with a credit note (BillingController, issueCreditNote with the discrepancy); the
 * two-step propose/accept, escalation, arbitration and claims are deferred.
 */
@RestController
class DisputeController implements DisputeApi {

    private final DiscrepancyQueries queries;
    private final SettleDiscrepancyHandler settle;
    private final CurrentScope currentScope;

    DisputeController(DiscrepancyQueries queries, SettleDiscrepancyHandler settle, CurrentScope currentScope) {
        this.queries = queries;
        this.settle = settle;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<DiscrepancyResponse> settleDiscrepancy(
            String idempotencyKey, UUID discrepancyId, SettleDiscrepancyRequest request) {
        ScopeContext scope = currentScope.get();
        settle.handle(new SettleDiscrepancy(discrepancyId, request.getReason()), scope);
        return ResponseEntity.ok(queries.getDiscrepancy(discrepancyId, scope)
                .map(DisputeController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.discrepancy.not_found")));
    }

    @Override
    public ResponseEntity<List<DiscrepancyResponse>> listDiscrepancies(String role) {
        return ResponseEntity.ok(queries.listDiscrepancies(OrderQueries.Role.valueOf(role), currentScope.get()).stream()
                .map(DisputeController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<DiscrepancyResponse> getDiscrepancy(UUID discrepancyId) {
        return ResponseEntity.ok(queries.getDiscrepancy(discrepancyId, currentScope.get())
                .map(DisputeController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.discrepancy.not_found")));
    }

    static DiscrepancyResponse toResponse(DiscrepancyView view) {
        DiscrepancyResponse response = new DiscrepancyResponse(
                view.discrepancyId(),
                DiscrepancyResponse.StatusEnum.fromValue(view.status()),
                DiscrepancyResponse.KindEnum.fromValue(view.kind()),
                view.buyerEntityId(),
                view.sellerEntityId(),
                view.grnId(),
                view.windowEndsAt(),
                view.lines().stream()
                        .map(line -> {
                            DiscrepancyLineResponse row = new DiscrepancyLineResponse(
                                    line.lineId(),
                                    line.grnLineId(),
                                    line.receivedQty(),
                                    line.damagedQty(),
                                    line.varianceQty());
                            row.setSkuId(line.skuId());
                            row.setBatchId(line.batchId());
                            row.setUomCode(line.uomCode());
                            row.setExpectedQty(line.expectedQty());
                            row.setUnitPrice(line.unitPrice());
                            return row;
                        })
                        .toList());
        response.setDocNumber(view.docNumberDisplay());
        response.setGrnDocNumber(view.grnDocNumberDisplay());
        response.setDeliveryNoteId(view.deliveryNoteId());
        response.setRaisedAt(view.raisedAt());
        response.setInvoiceId(view.invoiceId());
        response.setCreditNoteId(view.creditNoteId());
        response.setCreditNoteDocNumber(view.creditNoteDocNumberDisplay());
        response.setSettledAt(view.settledAt());
        response.setSettledByUserId(view.settledByUserId());
        response.setSettlementReason(view.settlementReason());
        return response;
    }
}
