package lk.coopfed.knoweb.m4trading.internal.order;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The ORD type's validator (19A section 7: "the type-specific validator registered by the owning
 * module"): an order is issued with at least one line, each with a positive quantity (24A section
 * 6, SubmitOrder). The parties and the owner are the base's checks.
 */
@Component
class OrderTypeHandler implements DocumentTypeHandler {

    static final String ORD = "ORD";

    @Override
    public String docTypeCode() {
        return ORD;
    }

    @Override
    public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
        if (lines == null || lines.isEmpty()) {
            throw new ProblemException("m4.order.lines_required");
        }
        for (DocumentLineRecord line : lines) {
            if (line.qty() == null || line.qty().signum() <= 0) {
                throw new ProblemException("m4.order.qty_not_positive");
            }
        }
    }

    @Override
    public boolean isReversible(DocumentRecord original) {
        return false; // an order is cancelled, never reversed (doc 24 section 3.1)
    }
}
