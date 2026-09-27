package lk.coopfed.knoweb.m4trading.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.web.generated.BillingApi;
import lk.coopfed.knoweb.m4trading.web.generated.InvoiceLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.InvoiceResponse;
import lk.coopfed.knoweb.m4trading.web.generated.IssueInvoiceRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/** The invoice operations of 24A section 5 (BillingController): issue and read. */
@RestController
class BillingController implements BillingApi {

    private final IssueInvoiceHandler issue;
    private final InvoiceQueries queries;
    private final CurrentScope currentScope;

    BillingController(IssueInvoiceHandler issue, InvoiceQueries queries, CurrentScope currentScope) {
        this.issue = issue;
        this.queries = queries;
        this.currentScope = currentScope;
    }

    @Override
    public ResponseEntity<InvoiceResponse> issueInvoice(String idempotencyKey, IssueInvoiceRequest request) {
        ScopeContext scope = currentScope.get();
        UUID invoiceId = issue.handle(new IssueInvoice(request.getGrnIds()), scope);
        return ResponseEntity.created(URI.create("/v1/trading/invoices/" + invoiceId))
                .body(read(invoiceId, scope));
    }

    @Override
    public ResponseEntity<InvoiceResponse> getInvoice(UUID invoiceId) {
        return ResponseEntity.ok(read(invoiceId, currentScope.get()));
    }

    @Override
    public ResponseEntity<List<InvoiceResponse>> listInvoices(String role) {
        return ResponseEntity.ok(queries.listInvoices(OrderQueries.Role.valueOf(role), currentScope.get()).stream()
                .map(BillingController::toResponse)
                .toList());
    }

    private InvoiceResponse read(UUID invoiceId, ScopeContext scope) {
        return queries.getInvoice(invoiceId, scope)
                .map(BillingController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
    }

    static InvoiceResponse toResponse(InvoiceView invoice) {
        InvoiceResponse response = new InvoiceResponse(
                invoice.invoiceId(),
                invoice.status(),
                invoice.sellerEntityId(),
                invoice.buyerEntityId(),
                invoice.grnIds(),
                invoice.taxPointDate(),
                invoice.dueDate(),
                invoice.netAmount(),
                invoice.taxAmount(),
                invoice.grossAmount(),
                invoice.lines().stream().map(BillingController::toLine).toList());
        response.setDocNumber(invoice.docNumberDisplay());
        response.setRelationshipId(invoice.relationshipId());
        response.setSellerVatNo(invoice.sellerVatNo());
        response.setBuyerVatNo(invoice.buyerVatNo());
        response.setIssuedAt(invoice.issuedAt());
        return response;
    }

    private static InvoiceLineResponse toLine(InvoiceView.InvoiceLineView line) {
        InvoiceLineResponse response = new InvoiceLineResponse(
                line.lineId(),
                line.lineNo(),
                line.skuId(),
                line.uomCode(),
                line.qty(),
                line.unitPrice(),
                line.taxRatePercent(),
                line.taxAmount(),
                line.lineTotal());
        response.setBatchId(line.batchId());
        response.setGrnLineId(line.grnLineId());
        return response;
    }
}
