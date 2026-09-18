package lk.coopfed.archfixtures.m1party;

import jakarta.persistence.Table;

/** Violates R4: an entity mapped to a schema its module does not own. */
@Table(name = "wrong", schema = "trading")
public class WrongSchemaEntity {
}
