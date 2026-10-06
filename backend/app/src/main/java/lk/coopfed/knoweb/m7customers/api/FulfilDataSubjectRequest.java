package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * FulfilAccess / FulfilErasure (27A section 6), and the fulfilment of a correction: by the
 * society's responsible officer, with a fresh second factor. ERASURE anonymises the customer (27A
 * section 6.4); ACCESS records the export handed over; CORRECTION records what was corrected.
 *
 * @param outcome what the officer did, in a sentence (required for a correction)
 */
public record FulfilDataSubjectRequest(UUID requestId, String outcome) {}
