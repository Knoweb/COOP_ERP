package lk.coopfed.knoweb.m4trading.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.A4Renderer;
import lk.coopfed.knoweb.kernel.api.CurrentScope;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.m4trading.api.DisputeInvoice;
import lk.coopfed.knoweb.m4trading.api.IssueCreditNote;
import lk.coopfed.knoweb.m4trading.api.IssueInvoice;
import lk.coopfed.knoweb.m4trading.api.ResolveInvoiceDispute;
import lk.coopfed.knoweb.m4trading.internal.invoice.DisputeInvoiceHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueCreditNoteHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.IssueInvoiceHandler;
import lk.coopfed.knoweb.m4trading.internal.invoice.ResolveInvoiceDisputeHandler;
import lk.coopfed.knoweb.m4trading.query.CreditNoteQueries;
import lk.coopfed.knoweb.m4trading.query.CreditNoteView;
import lk.coopfed.knoweb.m4trading.query.InvoiceQueries;
import lk.coopfed.knoweb.m4trading.query.InvoiceView;
import lk.coopfed.knoweb.m4trading.query.OrderQueries;
import lk.coopfed.knoweb.m4trading.web.generated.BillingApi;
import lk.coopfed.knoweb.m4trading.web.generated.CreditNoteLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.CreditNotePrintResponse;
import lk.coopfed.knoweb.m4trading.web.generated.CreditNoteResponse;
import lk.coopfed.knoweb.m4trading.web.generated.CreditNoteSummary;
import lk.coopfed.knoweb.m4trading.web.generated.DisputeInvoiceRequest;
import lk.coopfed.knoweb.m4trading.web.generated.InvoiceLineResponse;
import lk.coopfed.knoweb.m4trading.web.generated.InvoicePrintResponse;
import lk.coopfed.knoweb.m4trading.web.generated.InvoiceResponse;
import lk.coopfed.knoweb.m4trading.web.generated.IssueCreditNoteRequest;
import lk.coopfed.knoweb.m4trading.web.generated.IssueInvoiceRequest;
import lk.coopfed.knoweb.m4trading.web.generated.ResolveInvoiceDisputeRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

/**
 * The billing operations of 24A section 5 (BillingController): invoices (issue, read, print,
 * dispute, resolve the dispute) and credit notes (issue, read, print).
 */
@RestController
class BillingController implements BillingApi {

    private final IssueInvoiceHandler issue;
    private final DisputeInvoiceHandler dispute;
    private final ResolveInvoiceDisputeHandler resolveDispute;
    private final IssueCreditNoteHandler issueCreditNote;
    private final InvoiceQueries queries;
    private final CreditNoteQueries creditNotes;
    private final CurrentScope currentScope;
    private final A4Renderer renderer;

    BillingController(
            IssueInvoiceHandler issue,
            DisputeInvoiceHandler dispute,
            ResolveInvoiceDisputeHandler resolveDispute,
            IssueCreditNoteHandler issueCreditNote,
            InvoiceQueries queries,
            CreditNoteQueries creditNotes,
            CurrentScope currentScope,
            A4Renderer renderer) {
        this.issue = issue;
        this.dispute = dispute;
        this.resolveDispute = resolveDispute;
        this.issueCreditNote = issueCreditNote;
        this.queries = queries;
        this.creditNotes = creditNotes;
        this.currentScope = currentScope;
        this.renderer = renderer;
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
        ScopeContext scope = currentScope.get();
        return ResponseEntity.ok(queries.listInvoices(OrderQueries.Role.valueOf(role), scope).stream()
                .map(invoice -> toResponse(invoice, scope))
                .toList());
    }

    @Override
    public ResponseEntity<InvoiceResponse> disputeInvoice(
            String idempotencyKey, UUID invoiceId, DisputeInvoiceRequest request) {
        ScopeContext scope = currentScope.get();
        dispute.handle(new DisputeInvoice(invoiceId, request.getReason()), scope);
        return ResponseEntity.ok(read(invoiceId, scope));
    }

    @Override
    public ResponseEntity<InvoiceResponse> resolveInvoiceDispute(
            String idempotencyKey, UUID invoiceId, ResolveInvoiceDisputeRequest request) {
        ScopeContext scope = currentScope.get();
        resolveDispute.handle(new ResolveInvoiceDispute(invoiceId, request == null ? null : request.getNote()), scope);
        return ResponseEntity.ok(read(invoiceId, scope));
    }

    @Override
    public ResponseEntity<InvoicePrintResponse> getInvoicePrint(UUID invoiceId) {
        ScopeContext scope = currentScope.get();
        // The invoice is read under row-level security first: only its seller or its buyer sees it,
        // anyone else gets m4.invoice.not_found and no link.
        InvoiceView invoice =
                queries.getInvoice(invoiceId, scope).orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
        String objectKey = queries.printObjectKey(invoiceId, scope)
                .orElseThrow(() -> new ProblemException("m4.invoice.print_not_ready"));
        if (invoice.sellerEntityId().equals(scope.entityId())) {
            return ResponseEntity.ok(new InvoicePrintResponse(invoiceId, renderer.presignGet(objectKey, scope)));
        }
        // The buyer prints the same PDF, stored under the seller: the kernel is asked for the one key
        // this invoice names, and checks that it is the seller's report.
        return ResponseEntity.ok(new InvoicePrintResponse(
                invoiceId, renderer.presignGetOfParty(objectKey, invoice.sellerEntityId(), scope)));
    }

    @Override
    public ResponseEntity<CreditNoteResponse> issueCreditNote(String idempotencyKey, IssueCreditNoteRequest request) {
        ScopeContext scope = currentScope.get();
        List<IssueCreditNote.Line> lines = request.getLines() == null
                ? List.of()
                : request.getLines().stream()
                        .map(line -> new IssueCreditNote.Line(line.getInvoiceLineId(), line.getQty()))
                        .toList();
        UUID creditNoteId = issueCreditNote.handle(
                new IssueCreditNote(request.getInvoiceId(), request.getDiscrepancyId(), lines, request.getReason()),
                scope);
        return ResponseEntity.created(URI.create("/v1/trading/credit-notes/" + creditNoteId))
                .body(readCreditNote(creditNoteId, scope));
    }

    @Override
    public ResponseEntity<CreditNoteResponse> getCreditNote(UUID creditNoteId) {
        return ResponseEntity.ok(readCreditNote(creditNoteId, currentScope.get()));
    }

    @Override
    public ResponseEntity<CreditNotePrintResponse> getCreditNotePrint(UUID creditNoteId) {
        ScopeContext scope = currentScope.get();
        CreditNoteView note = creditNotes
                .getCreditNote(creditNoteId, scope)
                .orElseThrow(() -> new ProblemException("m4.creditnote.not_found"));
        String objectKey = creditNotes
                .printObjectKey(creditNoteId, scope)
                .orElseThrow(() -> new ProblemException("m4.creditnote.print_not_ready"));
        if (note.sellerEntityId().equals(scope.entityId())) {
            return ResponseEntity.ok(new CreditNotePrintResponse(creditNoteId, renderer.presignGet(objectKey, scope)));
        }
        return ResponseEntity.ok(new CreditNotePrintResponse(
                creditNoteId, renderer.presignGetOfParty(objectKey, note.sellerEntityId(), scope)));
    }

    private InvoiceResponse read(UUID invoiceId, ScopeContext scope) {
        return queries.getInvoice(invoiceId, scope)
                .map(invoice -> toResponse(invoice, scope))
                .orElseThrow(() -> new ProblemException("m4.invoice.not_found"));
    }

    private CreditNoteResponse readCreditNote(UUID creditNoteId, ScopeContext scope) {
        return creditNotes
                .getCreditNote(creditNoteId, scope)
                .map(BillingController::toResponse)
                .orElseThrow(() -> new ProblemException("m4.creditnote.not_found"));
    }

    private InvoiceResponse toResponse(InvoiceView invoice, ScopeContext scope) {
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
        queries.balance(invoice.invoiceId(), scope).ifPresent(balance -> {
            response.setCreditedAmount(balance.creditedAmount());
            response.setAmountDue(balance.amountDue());
            response.setDisputed(balance.disputed());
            response.setDisputeReason(balance.disputeReason());
        });
        response.setCreditNotes(creditNotes.creditNotesOf(invoice.invoiceId(), scope).stream()
                .map(note -> {
                    CreditNoteSummary summary = new CreditNoteSummary(note.creditNoteId(), note.grossAmount());
                    summary.setDocNumber(note.docNumberDisplay());
                    return summary;
                })
                .toList());
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

    static CreditNoteResponse toResponse(CreditNoteView note) {
        CreditNoteResponse response = new CreditNoteResponse(
                note.creditNoteId(),
                note.status(),
                note.invoiceId(),
                note.sellerEntityId(),
                note.buyerEntityId(),
                note.reason(),
                note.netAmount(),
                note.taxAmount(),
                note.grossAmount(),
                note.lines().stream()
                        .map(line -> {
                            CreditNoteLineResponse row = new CreditNoteLineResponse(
                                    line.lineId(),
                                    line.lineNo(),
                                    line.skuId(),
                                    line.uomCode(),
                                    line.qty(),
                                    line.unitPrice(),
                                    line.taxRatePercent(),
                                    line.taxAmount(),
                                    line.lineTotal());
                            row.setBatchId(line.batchId());
                            row.setInvoiceLineId(line.grnLineId());
                            return row;
                        })
                        .toList());
        response.setDocNumber(note.docNumberDisplay());
        response.setInvoiceDocNumber(note.invoiceDocNumberDisplay());
        response.setDiscrepancyId(note.discrepancyId());
        response.setIssuedAt(note.issuedAt());
        return response;
    }
}
