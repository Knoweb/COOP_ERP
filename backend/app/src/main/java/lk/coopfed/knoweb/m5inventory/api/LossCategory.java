package lk.coopfed.knoweb.m5inventory.api;

/** The categories of loss (doc 25 section 3.8, F-01): every write-off carries one, and reports split by it. */
public enum LossCategory {
    DAMAGED_IN_TRANSIT,
    DAMAGED_IN_STORE,
    EXPIRED,
    THEFT,
    SHRINKAGE_UNEXPLAINED,
    STAFF_CONSUMPTION,
    SAMPLES,
    DONATION,
    OTHER
}
