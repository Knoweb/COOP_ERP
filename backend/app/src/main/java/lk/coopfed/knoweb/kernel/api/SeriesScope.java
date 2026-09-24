package lk.coopfed.knoweb.kernel.api;

/**
 * Where a numbering series lives (doc 18 section 5.5; 24B N-3): one counter per document
 * type per entity, per shop location, or per till position. The counter's holder differs
 * with the scope: central for ENTITY, the shop's primary till for LOCATION, the assigned
 * device for TILL_POSITION.
 */
public enum SeriesScope {
    ENTITY,
    LOCATION,
    TILL_POSITION
}
