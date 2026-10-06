package lk.coopfed.knoweb.engine

/**
 * The version every result carries (23A section 6), so a receipt can be re-resolved by the same
 * engine. Tagged 1.0.0 at the M3 contract freeze (23A ticket M3-11); 0.x until then.
 *
 * 0.2.0 (CR-23A-1): expired batches are not candidates, an expiry markdown follows the identified
 * or FEFO-first batch and never matches past expiry, a zero-benefit line rule no longer wins.
 */
const val ENGINE_VERSION: String = "0.2.0"
