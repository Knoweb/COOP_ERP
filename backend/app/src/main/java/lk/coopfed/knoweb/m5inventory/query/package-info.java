/**
 * The published reads of M5 (25A section 4: "query/ InventoryQueries"). Other modules (M4's order
 * acceptance and delivery notes, M3's authoring checks, M8) depend on this package and never on
 * M5's internals. Every method takes the caller's scope; row-level security does the filtering.
 */
@org.springframework.modulith.NamedInterface("query")
package lk.coopfed.knoweb.m5inventory.query;
