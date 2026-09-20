package lk.coopfed.archfixtures.m1party;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;

import java.util.UUID;

/**
 * Violates R4 in the way the first version of the rule missed: an entity with no @Table at all.
 * Hibernate maps it to a table named after the class, in whatever schema the search path names.
 */
@Entity
public class EntityWithoutTable {

    @Id
    private UUID id;
}
