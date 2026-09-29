package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * RecordDataSubjectRequest (27A section 6, doc 27 section 4.4): a customer asks the society that
 * registered them for their data (ACCESS), for a correction (CORRECTION) or to be forgotten
 * (ERASURE).
 */
public record RecordDataSubjectRequest(UUID customerId, String kind, String notes) {

    public static final String ACCESS = "ACCESS";
    public static final String CORRECTION = "CORRECTION";
    public static final String ERASURE = "ERASURE";
}
