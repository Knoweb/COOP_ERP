package lk.coopfed.knoweb.m1party.internal.relationship;

/**
 * The lock a relationship handler takes on the row it changes (a fragment of
 * {@link RelationshipRepository}; Spring Data joins {@link RelationshipLockingImpl} to it by
 * name). The row is read first without a lock, so that a buyer, who may read it (party_read)
 * but never change it, is told {@code not_seller} rather than {@code not_found}: a
 * {@code SELECT ... FOR UPDATE} applies the update policy and shows the buyer nothing.
 */
interface RelationshipLocking {

    /**
     * Re-reads the row with {@code SELECT ... FOR UPDATE} and returns it as it now stands: what
     * another transaction committed meanwhile (a suspension, a closing date) is seen, and any
     * other handler of the row waits until this transaction ends (the review of M1-04).
     */
    Relationship lockForUpdate(Relationship relationship);
}
