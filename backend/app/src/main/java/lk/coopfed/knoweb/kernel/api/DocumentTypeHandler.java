package lk.coopfed.knoweb.kernel.api;

import java.util.List;

/**
 * What the owning module of a document type registers with the kernel (19A section 7: "the
 * type-specific validator registered by the owning module", "the owning module's post-issue
 * hook", "a predicate the owning module registers" for reversibility). One Spring bean per
 * document type code; the issuance protocol finds it by {@link #docTypeCode()} and refuses to
 * issue a type nobody owns.
 *
 * <p>Everything here runs inside the issuing transaction, after the base's own checks (draft
 * status, a known type, a counterparty where the type is bilateral) and before the number is
 * taken: no invalid document takes a number (doc 18 section 5.5, step 1).
 */
public interface DocumentTypeHandler {

    /** The code this handler owns, for example {@code GRN}. */
    String docTypeCode();

    /**
     * The type's own rules: parties, lines, ceilings, permissions. Throw a
     * {@link ProblemException} to refuse; the draft then keeps its status and takes no number.
     */
    default void validate(DocumentRecord draft, List<DocumentLineRecord> lines, ScopeContext ctx) {}

    /**
     * After the number, the hash, the state row and the audit record, in the same transaction:
     * stock movements and the like (19A section 7, step 4).
     */
    default void afterIssue(DocumentRecord issued, List<DocumentLineRecord> lines, ScopeContext ctx) {}

    /**
     * Whether a REVERSES link may still be made against this original (doc 18, document_link:
     * "only while reversible per the type's state machine"). The default admits any issued
     * document; a type with terminal states after which a reversal is a new business event
     * (a settled invoice) says so here.
     */
    default boolean isReversible(DocumentRecord original) {
        return original.isIssued();
    }
}
