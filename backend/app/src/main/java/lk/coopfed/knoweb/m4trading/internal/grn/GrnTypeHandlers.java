package lk.coopfed.knoweb.m4trading.internal.grn;

import java.util.List;
import lk.coopfed.knoweb.kernel.api.DocumentLineRecord;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The validators of the GRN and DISC types (19A section 7), registered by M4. */
@Configuration
class GrnTypeHandlers {

    @Bean
    DocumentTypeHandler grnTypeHandler() {
        return new DocumentTypeHandler() {
            @Override
            public String docTypeCode() {
                return GrnReads.GRN;
            }

            @Override
            public void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {
                if (lines == null || lines.isEmpty()) {
                    throw new ProblemException("m4.grn.lines_required");
                }
                for (DocumentLineRecord line : lines) {
                    if (line.qty() == null || line.qty().signum() < 0) {
                        throw new ProblemException(
                                "m4.grn.line_quantities", java.util.Map.of("skuId", String.valueOf(line.skuId())));
                    }
                }
            }

            @Override
            public boolean isReversible(DocumentRecord original) {
                return false; // ReverseGrn is deferred for the demo (M4-05)
            }
        };
    }

    @Bean
    DocumentTypeHandler discrepancyTypeHandler() {
        return new DocumentTypeHandler() {
            @Override
            public String docTypeCode() {
                return GrnReads.DISC;
            }

            @Override
            public boolean isReversible(DocumentRecord original) {
                return false;
            }
        };
    }
}
