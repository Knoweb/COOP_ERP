package lk.coopfed.archfixtures.m1party;

import jakarta.persistence.Table;

/** Violates R4: an entity that names no schema at all. */
@Table(name = "nowhere")
public class NoSchemaEntity {
}
