package lk.coopfed.knoweb.kernel.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The compensating-correction pattern (doc 18, table document_link; 19A section 7,
 * "DocumentLinks.link validates the rule per type"). An issued document never changes; a
 * correction is a new document that points back at the original through one typed link, and
 * the net position of an original is the original plus its linked corrections (C-I6).
 */
public interface DocumentLinks {

    /**
     * Links a later, correcting document to its original, after the rule of the link type:
     * <ul>
     *   <li>REVERSES: at most once per original, and only while the original's type says it
     *       is reversible ({@link DocumentTypeHandler#isReversible});</li>
     *   <li>SETTLES, CREDITS, DEBITS: carry an amount; SETTLES and CREDITS together may not
     *       exceed the original's gross amount (the open balance);</li>
     *   <li>SUPERSEDES: drafts only, on both sides;</li>
     *   <li>ADJUSTS, DISPUTES: the originals are issued documents.</li>
     * </ul>
     * Both documents must be visible to the caller and the correcting one owned by it.
     *
     * @param amount the amount applied to that original, for the types that carry one; null otherwise
     * @throws ProblemException {@code document.not_found}, {@code document.link.amount_required},
     *                          {@code document.link.amount_not_allowed}, {@code document.link.reversed_already},
     *                          {@code document.link.not_reversible}, {@code document.link.exceeds_balance},
     *                          {@code document.link.drafts_only}, {@code document.link.not_issued},
     *                          {@code document.link.self}
     */
    void link(UUID fromDocumentId, UUID toDocumentId, LinkType type, BigDecimal amount, ScopeContext ctx);
}
