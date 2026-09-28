package lk.coopfed.knoweb.m4trading.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.RecordChequeOutcome;
import lk.coopfed.knoweb.m4trading.api.RecordPaymentReceipt;
import lk.coopfed.knoweb.m4trading.internal.payment.RecordChequeOutcomeHandler;
import lk.coopfed.knoweb.m4trading.internal.payment.RecordPaymentReceiptHandler;
import lk.coopfed.knoweb.m4trading.query.ExposureQueries;
import lk.coopfed.knoweb.m4trading.query.ExposureView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentQueries;
import lk.coopfed.knoweb.m4trading.query.PaymentReceiptView;
import lk.coopfed.knoweb.m4trading.web.generated.AllocationResponse;
import lk.coopfed.knoweb.m4trading.web.generated.ChequeOutcomeRequest;
import lk.coopfed.knoweb.m4trading.web.generated.ChequeResponse;
import lk.coopfed.knoweb.m4trading.web.generated.ExposureResponse;
import lk.coopfed.knoweb.m4trading.web.generated.PaymentApi;
import lk.coopfed.knoweb.m4trading.web.generated.PaymentReceiptResponse;
import lk.coopfed.knoweb.m4trading.web.generated.RecordPaymentReceiptRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment operations of 24A section 5 (M4-07, M4-09): payment receipts (record, list, read,
 * the cheque's outcome) and the exposure of each relationship.
 */
@RestController
class PaymentController implements PaymentApi {

    private final RecordPaymentReceiptHandler record;
    private final RecordChequeOutcomeHandler chequeOutcome;
    private final PaymentQueries payments;
    private final ExposureQueries exposures;
    private final CurrentScope currentScope;

    PaymentController(
            RecordPaymentReceiptHandler record,
            RecordChequeOutcomeHandler chequeOutcome,
            PaymentQueries payments,
            ExposureQueries exposures,
            CurrentScope currentScope) {
        this.record = record;
        this.chequeOutcome = chequeOutcome;
        this.payments = payments;
        this.exposures = exposures;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<PaymentReceiptResponse> recordPaymentReceipt(
            String idempotencyKey, RecordPaymentReceiptRequest request) {
        ScopeContext scope = currentScope.get();
        RecordPaymentReceipt.Cheque cheque = request.getCheque() == null
                ? null
                : new RecordPaymentReceipt.Cheque(
                        request.getCheque().getBank(),
                        request.getCheque().getChequeNo(),
                        request.getCheque().getDated());
        List<RecordPaymentReceipt.Settlement> settlements = request.getSettlements() == null
                ? List.of()
                : request.getSettlements().stream()
                        .map(s -> new RecordPaymentReceipt.Settlement(s.getInvoiceId(), s.getAmount()))
                        .toList();
        UUID receiptId = record.handle(
                new RecordPaymentReceipt(
                        request.getBuyerEntityId(),
                        request.getMethod().getValue(),
                        request.getAmount(),
                        request.getReference(),
                        request.getReceivedOn(),
                        cheque,
                        settlements),
                scope);
        return ResponseEntity.created(URI.create("/v1/trading/payment-receipts/" + receiptId))
                .body(read(receiptId, scope));
    }

    @Override
    public ResponseEntity<List<PaymentReceiptResponse>> listPaymentReceipts(String role) {
        ScopeContext scope = currentScope.get();
        return ResponseEntity.ok(payments.listReceipts(OrderQueries.Role.valueOf(role), scope).stream()
                .map(PaymentController::toResponse)
                .toList());
    }

    @Override
    public ResponseEntity<PaymentReceiptResponse> getPaymentReceipt(UUID receiptId) {
        return ResponseEntity.ok(read(receiptId, currentScope.get()));
    }

    @Override
    public ResponseEntity<PaymentReceiptResponse> recordChequeOutcome(
            String idempotencyKey, UUID receiptId, ChequeOutcomeRequest request) {
        ScopeContext scope = currentScope.get();
        chequeOutcome.handle(
                new RecordChequeOutcome(receiptId, request.getOutcome().getValue(), request.getReason()), scope);
        return ResponseEntity.ok(read(receiptId, scope));
    }

    @Override
    public ResponseEntity<List<ExposureResponse>> listExposures(String role) {
        ScopeContext scope = currentScope.get();
        return ResponseEntity.ok(exposures.listExposures(OrderQueries.Role.valueOf(role), scope).stream()
                .map(PaymentController::toResponse)
                .toList());
    }

    private PaymentReceiptResponse read(UUID receiptId, ScopeContext scope) {
        return payments.getReceipt(receiptId, scope)
                .map(PaymentController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.payment.not_found"));
    }

    static PaymentReceiptResponse toResponse(PaymentReceiptView view) {
        PaymentReceiptResponse response = new PaymentReceiptResponse(
                view.receiptId(),
                PaymentReceiptResponse.StatusEnum.fromValue(view.status()),
                view.sellerEntityId(),
                view.buyerEntityId(),
                view.method(),
                view.receivedOn(),
                view.amount(),
                view.unappliedAmount(),
                view.allocations().stream()
                        .map(allocation -> {
                            AllocationResponse row =
                                    new AllocationResponse(allocation.invoiceId(), allocation.amount());
                            row.setInvoiceNumber(allocation.invoiceNumber());
                            return row;
                        })
                        .toList());
        response.setDocNumber(view.docNumberDisplay());
        response.setReference(view.reference());
        response.setIssuedAt(view.issuedAt());
        response.setReversalOf(view.reversalOf());
        response.setReversedBy(view.reversedBy());
        response.setReason(view.reason());
        if (view.cheque() != null) {
            ChequeResponse cheque = new ChequeResponse(
                    view.cheque().bank(),
                    view.cheque().chequeNo(),
                    view.cheque().dated());
            if (view.cheque().outcome() != null) {
                cheque.setOutcome(
                        ChequeResponse.OutcomeEnum.fromValue(view.cheque().outcome()));
            }
            cheque.setOutcomeAt(view.cheque().outcomeAt());
            response.setCheque(cheque);
        }
        return response;
    }

    static ExposureResponse toResponse(ExposureView view) {
        ExposureResponse response = new ExposureResponse(
                view.relationshipId(),
                view.sellerEntityId(),
                view.buyerEntityId(),
                view.openInvoices(),
                view.acceptedNotInvoiced(),
                view.unappliedReceipts(),
                view.amount(),
                view.asOf());
        response.setCreditLimit(view.creditLimit());
        response.setWarnThresholdPercent(view.warnThresholdPercent());
        return response;
    }
}
