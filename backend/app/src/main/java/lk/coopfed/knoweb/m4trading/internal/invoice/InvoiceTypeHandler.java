package lk.coopfed.knoweb.m4trading.internal.invoice;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/** The INV type's validator: a tax invoice is issued with at least one priced, taxed line. */
@Component
class InvoiceTypeHandler implements DocumentTypeHandler {

    @Override
    public String docTypeCode() {
        return IssueInvoiceHandler.INV;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (lines == null || lines.isEmpty()) {
            throw new ProblemException("m4.invoice.nothing_received");
        }
        for (DocumentLineRecord line : lines) {
            if (line.unitPrice() == null || line.taxRatePercent() == null || line.lineTotal() == null) {
                throw new ProblemException("m4.invoice.line_unpriced");
            }
        }
    }
}
