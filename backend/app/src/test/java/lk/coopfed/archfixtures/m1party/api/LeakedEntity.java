package lk.coopfed.archfixtures.m1party.api;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Violates the published-package rule: a JPA entity in a module api package. Every module that
 * compiles against this api would hold the table itself, not a record of what it may see.
 */
@Entity
@Table(schema = "party", name = "leaked")
public class LeakedEntity {

    @Id
    private UUID id;
}
