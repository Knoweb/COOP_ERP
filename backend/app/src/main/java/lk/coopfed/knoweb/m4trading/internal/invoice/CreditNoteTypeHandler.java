package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The CN type's validator: a credit note is issued against an invoice with at least one priced,
 * taxed line. It is never reversed; a credit note given in error is answered by a debit note
 * (deferred with IssueDebitNote).
 */
@Component
class CreditNoteTypeHandler implements DocumentTypeHandler {

    @Override
    public String docTypeCode() {
        return IssueCreditNoteHandler.CN;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (draft.referenceDocumentId() == null) {
            throw new ProblemException("m4.invoice.not_found");
        }
        if (lines == null || lines.isEmpty()) {
            throw new ProblemException("m4.creditnote.nothing_to_credit");
        }
        for (DocumentLineRecord line : lines) {
            if (line.unitPrice() == null || line.taxRatePercent() == null || line.lineTotal() == null) {
                throw new ProblemException("m4.invoice.line_unpriced");
            }
        }
    }

    @Override
    public boolean isReversible(DocumentRecord original) {
        return false;
    }
}
