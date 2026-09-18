package lk.coopfed.knoweb.kernel.api;

/** Where a document was issued (doc 18 §5, column {@code origin}). Offline documents carry their device sequence. */
public enum DocumentOrigin {
    ONLINE,
    OFFLINE
}
