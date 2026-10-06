package lk.coopfed.knoweb.m7customers.query;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The customer card of the society office (27A section 8): identity, phone and its history,
 * consents, tags, attributes and the account at the caller's society. The NIC shows as its last
 * four characters only.
 *
 * @param account null when the caller's society has no account for the customer
 */
public record CustomerCard(
        UUID customerId,
        String displayName,
        String displayNameSi,
        String displayNameTa,
        String language,
        String phone,
        String nicLast4,
        String status,
        Instant registeredAt,
        boolean registeredHere,
        Map<String, String> attributes,
        List<String> tags,
        List<Consent> consents,
        List<Phone> phones,
        AccountView account) {

    /** A consent: its purpose, how and when it was given, and when withdrawn (null while in force). */
    public record Consent(String purpose, String grantedVia, Instant grantedAt, Instant withdrawnAt) {}

    /** A number the customer has held as primary, newest first; validTo null for the current one. */
    public record Phone(String phone, Instant validFrom, Instant validTo, String reason) {}
}
