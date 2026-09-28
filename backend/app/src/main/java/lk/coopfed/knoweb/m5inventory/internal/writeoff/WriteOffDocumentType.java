package lk.coopfed.knoweb.m5inventory.internal.writeoff;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * M5 owns the WOF document type (seed/kernel/document-types.yaml: owning_module m5inventory). A
 * write-off has a location and at least one line with a batch, a quantity above zero and its loss
 * category. It is not reversed: a wrong write-off is corrected by a count.
 */
@Component
class WriteOffDocumentType implements DocumentTypeHandler {

    static final String WOF = "WOF";

    @Override
    public String docTypeCode() {
        return WOF;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (draft.locationId() == null
                || lines.isEmpty()
                || lines.stream()
                        .anyMatch(l -> l.batchId() == null
                                || l.qty() == null
                                || l.qty().signum() <= 0
                                || l.lossCategory() == null)) {
            throw new ProblemException("m5.writeoff.line_invalid");
        }
    }

    @Override
    public boolean isReversible(DocumentRecord original) {
        return false;
    }
}
