package lk.coopfed.knoweb.m5inventory.internal.opening;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * M5 owns the OPB document type (seed/kernel/document-types.yaml: owning_module m5inventory); the
 * kernel's issuance protocol refuses a type no module owns. An opening balance has a location and
 * at least one line with a batch and a quantity above zero.
 */
@Component
class OpeningBalanceDocumentType implements DocumentTypeHandler {

    static final String OPB = "OPB";

    @Override
    public String docTypeCode() {
        return OPB;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (draft.locationId() == null
                || lines.isEmpty()
                || lines.stream()
                        .anyMatch(l -> l.batchId() == null
                                || l.qty() == null
                                || l.qty().signum() <= 0)) {
            throw new ProblemException("m5.opening.line_invalid");
        }
    }

    /** An opening balance is not reversed: a wrong count is corrected by a count adjustment. */
    @Override
    public boolean isReversible(DocumentRecord original) {
        return false;
    }
}
