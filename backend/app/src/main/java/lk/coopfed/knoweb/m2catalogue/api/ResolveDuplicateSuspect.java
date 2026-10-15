package lk.coopfed.knoweb.m2catalogue.api;

import java.util.UUID;

/**
 * Command to resolve an open duplicate suspect pair (CAT-01, 22A section 5).
 */
public record ResolveDuplicateSuspect(UUID suspectId, String decision, UUID canonicalSkuId) {

    public static final String MERGED = "MERGED";
    public static final String DISMISSED = "DISMISSED";
}
