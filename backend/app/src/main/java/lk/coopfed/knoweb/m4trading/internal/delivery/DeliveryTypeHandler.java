package lk.coopfed.knoweb.m4trading.internal.delivery;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/** The DN type's validator: a delivery note is issued with at least one line of positive quantity. */
@Component
class DeliveryTypeHandler implements DocumentTypeHandler {

    @Override
    public String docTypeCode() {
        return DeliveryReads.DN;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (lines == null || lines.isEmpty()) {
            throw new ProblemException("m4.delivery.lines_required");
        }
        for (DocumentLineRecord line : lines) {
            if (line.qty() == null || line.qty().signum() <= 0) {
                throw new ProblemException("m4.delivery.qty_not_positive");
            }
        }
    }

    @Override
    public boolean isReversible(DocumentRecord original) {
        return false; // a delivery is corrected by the GRN and its discrepancy, never reversed (doc 24)
    }
}
