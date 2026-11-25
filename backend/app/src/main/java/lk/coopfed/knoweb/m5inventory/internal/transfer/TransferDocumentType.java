package lk.coopfed.knoweb.m5inventory.internal.transfer;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * M5 owns the XFR document type. A transfer is issued at its source location and freezes the
 * batch, quantity and outbound cost of every line in the kernel document base.
 */
@Component
class TransferDocumentType implements DocumentTypeHandler {

    static final String XFR = "XFR";

    @Override
    public String docTypeCode() {
        return XFR;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (draft.locationId() == null
                || lines.isEmpty()
                || lines.stream()
                        .anyMatch(line -> line.skuId() == null
                                || line.batchId() == null
                                || line.qty() == null
                                || line.qty().signum() <= 0
                                || line.unitCostAtIssue() == null
                                || line.unitCostAtIssue().signum() < 0)) {
            throw new ProblemException("m5.transfer.line_invalid");
        }
    }
}
