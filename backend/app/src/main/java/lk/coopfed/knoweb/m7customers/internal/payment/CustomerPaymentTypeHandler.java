package lk.coopfed.knoweb.m7customers.internal.payment;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The CPR type's validator (27A section 2: "DocumentService.issue for CPR"): a customer payment
 * receipt is one line carrying the amount received (no item), so the issued header's gross is the
 * amount. A reversal (M7-08, later) names the receipt it reverses and is not reversible itself.
 */
@Component
class CustomerPaymentTypeHandler implements DocumentTypeHandler {

    @Override
    public String docTypeCode() {
        return RecordCustomerPaymentHandler.CPR;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (lines == null || lines.size() != 1 || lines.get(0).lineTotal() == null) {
            throw new ProblemException("m7.payment.amount_invalid");
        }
    }

    @Override
    public boolean isReversible(DocumentRecord original) {
        return original.isIssued() && original.referenceDocumentId() == null;
    }
}
