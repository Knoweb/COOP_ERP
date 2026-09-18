package lk.coopfed.knoweb.kernel.api;

/**
 * Typed relationships between documents: the compensating-correction pattern
 * (doc 18 §5, table {@code document_link}). Issued documents are never changed;
 * every correction is a new document pointing back through one of these.
 */
public enum LinkType {

    /** Full void: same lines negated. At most once per original, only while the original is reversible. */
    REVERSES,

    /** Credit note against an invoice; refund receipt against a receipt. Carries an amount. */
    CREDITS,

    /** Debit note against an invoice. */
    DEBITS,

    /** Count adjustment against a count; correction receipt with the delta only. */
    ADJUSTS,

    /** Payment receipt against specific invoices. Carries the amount applied to that original. */
    SETTLES,

    /** Discrepancy or claim against a GRN or delivery note. */
    DISPUTES,

    /** Re-issued draft; drafts only. */
    SUPERSEDES
}
