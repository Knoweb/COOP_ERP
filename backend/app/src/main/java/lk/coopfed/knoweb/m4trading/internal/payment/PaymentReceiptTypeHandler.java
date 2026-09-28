package lk.coopfed.knoweb.m4trading.internal.payment;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The PRC type's validator: a payment receipt is one line carrying its amount (no item), so the
 * issued header's gross is the amount received; a reversal's line carries it negated. A receipt
 * is reversible once, and a reversal (which names the receipt it reverses) is not.
 */
@Component
class PaymentReceiptTypeHandler implements DocumentTypeHandler {

    @Override
    public String docTypeCode() {
        return RecordPaymentReceiptHandler.PRC;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (lines == null || lines.size() != 1 || lines.get(0).lineTotal() == null) {
            throw new ProblemException("m4.payment.amount_invalid");
        }
    }

    @Override
    public boolean isReversible(DocumentRecord original) {
        return original.isIssued() && original.referenceDocumentId() == null;
    }
}
