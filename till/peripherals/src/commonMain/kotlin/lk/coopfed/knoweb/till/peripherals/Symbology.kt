package lk.coopfed.knoweb.till.peripherals

/**
 * Symbologies recognized by the till's scan layer (doc 26 section 3.6, doc 26A section 7).
 */
enum class Symbology {
    EAN13,
    EAN8,
    UPCA,
    GS1_128,
    GS1_DATAMATRIX,
    GS1_QR,
    INTERNAL_20_29,
    UNKNOWN,
}
