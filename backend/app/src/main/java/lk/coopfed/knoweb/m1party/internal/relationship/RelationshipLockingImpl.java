package lk.coopfed.knoweb.m1party.internal.relationship;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

/** See {@link RelationshipLocking}. */
class RelationshipLockingImpl implements RelationshipLocking {

    private final EntityManager entityManager;

    RelationshipLockingImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public Relationship lockForUpdate(Relationship relationship) {
        // refresh with a lock: one SELECT ... FOR UPDATE that both waits for the row and
        // replaces what was read before with what is committed now.
        entityManager.refresh(relationship, LockModeType.PESSIMISTIC_WRITE);
        return relationship;
    }
}
