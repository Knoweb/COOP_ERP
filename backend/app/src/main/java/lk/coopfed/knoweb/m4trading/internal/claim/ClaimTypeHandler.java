package lk.coopfed.knoweb.m4trading.internal.claim;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/** The validator of the CLM type (19A section 7), registered by M4: a claim has lines, each above zero. */
@Component
class ClaimTypeHandler implements DocumentTypeHandler {

    @Override
    public String docTypeCode() {
        return ClaimReads.CLM;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (lines == null || lines.isEmpty()) {
            throw new ProblemException("m4.claim.lines_required");
        }
        for (DocumentLineRecord line : lines) {
            if (line.qty() == null || line.qty().signum() <= 0) {
                throw new ProblemException(
                        "m4.claim.qty_invalid", java.util.Map.of("skuId", String.valueOf(line.skuId())));
            }
        }
    }

    @Override
    public boolean isReversible(DocumentRecord original) {
        return false; // a claim is decided, never reversed
    }
}
