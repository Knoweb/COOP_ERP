package lk.coopfed.knoweb.kernel.internal.document;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.AuditFacade;
import lk.coopfed.knoweb.kernel.api.DocumentBaseRepository;
import lk.coopfed.knoweb.kernel.api.DocumentLinkRecord;
import lk.coopfed.knoweb.kernel.api.DocumentLinks;
import lk.coopfed.knoweb.kernel.api.DocumentRecord;
import lk.coopfed.knoweb.kernel.api.DocumentTypeHandler;
import lk.coopfed.knoweb.kernel.api.LinkType;
import lk.coopfed.knoweb.kernel.api.ProblemException;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import lk.coopfed.knoweb.kernel.api.Subject;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The rules of {@code kernel.document_link} (doc 18, table document_link; 19A section 7).
 * Runs in the caller's transaction and scope; a document the caller cannot see is not found,
 * and a link the caller does not own is refused by the table's policy.
 */
@Component
class DocumentLinksImpl implements DocumentLinks {

    static final String AUDIT_LINKED = "DOCUMENT_LINKED";

    private final DocumentBaseRepository documents;
    private final Map<String, DocumentTypeHandler> handlers = new HashMap<>();
    private final AuditFacade audit;
    private final Clock clock;

    DocumentLinksImpl(
            DocumentBaseRepository documents, List<DocumentTypeHandler> typeHandlers, AuditFacade audit, Clock clock) {
        this.documents = documents;
        this.audit = audit;
        this.clock = clock;
        typeHandlers.forEach(handler -> handlers.putIfAbsent(handler.docTypeCode(), handler));
    }

    @Override
    public void link(UUID fromDocumentId, UUID toDocumentId, LinkType type, BigDecimal amount, ScopeContext ctx) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("DocumentLinks.link was called outside a transaction");
        }

        if (fromDocumentId == null || toDocumentId == null || type == null) {
            throw new IllegalArgumentException("A link names both documents and its type");
        }

        if (fromDocumentId.equals(toDocumentId)) {
            throw new ProblemException("document.link.self");
        }

        DocumentRecord from =
                documents.findById(fromDocumentId).orElseThrow(() -> new ProblemException("document.not_found"));
        DocumentRecord to =
                documents.findById(toDocumentId).orElseThrow(() -> new ProblemException("document.not_found"));

        boolean carriesAmount = type == LinkType.SETTLES || type == LinkType.CREDITS || type == LinkType.DEBITS;

        if (carriesAmount && (amount == null || amount.signum() <= 0)) {
            throw new ProblemException("document.link.amount_required", Map.of("linkType", type.name()));
        }

        if (!carriesAmount && amount != null) {
            throw new ProblemException("document.link.amount_not_allowed", Map.of("linkType", type.name()));
        }

        List<DocumentLinkRecord> existing = documents.findLinks(toDocumentId);

        switch (type) {
            case SUPERSEDES -> {
                if (from.isIssued() || to.isIssued()) {
                    throw new ProblemException("document.link.drafts_only");
                }
            }
            case REVERSES -> {
                requireIssued(to);
                boolean reversedAlready = existing.stream()
                        .anyMatch(link -> link.linkType() == LinkType.REVERSES
                                && link.toDocumentId().equals(toDocumentId));
                if (reversedAlready) {
                    throw new ProblemException("document.link.reversed_already");
                }
                DocumentTypeHandler handler = handlers.get(to.docTypeCode());
                boolean reversible = handler == null ? to.isIssued() : handler.isReversible(to);
                if (!reversible) {
                    throw new ProblemException("document.link.not_reversible", Map.of("docTypeCode", to.docTypeCode()));
                }
            }
            case SETTLES, CREDITS -> {
                requireIssued(to);
                BigDecimal applied = existing.stream()
                        .filter(link -> link.toDocumentId().equals(toDocumentId))
                        .filter(link -> link.linkType() == LinkType.SETTLES || link.linkType() == LinkType.CREDITS)
                        .map(DocumentLinkRecord::amount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal gross = to.grossAmount() == null ? BigDecimal.ZERO : to.grossAmount();
                if (applied.add(amount).compareTo(gross) > 0) {
                    throw new ProblemException(
                            "document.link.exceeds_balance",
                            Map.of("open", gross.subtract(applied).toPlainString(), "amount", amount.toPlainString()));
                }
            }
            case DEBITS, ADJUSTS, DISPUTES -> requireIssued(to);
        }

        documents.addLink(
                new DocumentLinkRecord(fromDocumentId, toDocumentId, type, amount, clock.instant(), ctx.userId()));

        audit.record(
                AUDIT_LINKED,
                Subject.of("document", fromDocumentId),
                null,
                Map.of(
                        "linkType", type.name(),
                        "toDocumentId", toDocumentId.toString(),
                        "amount", amount == null ? "" : amount.toPlainString()),
                ctx);
    }

    private static void requireIssued(DocumentRecord original) {
        if (!original.isIssued()) {
            throw new ProblemException("document.link.not_issued");
        }
    }
}
