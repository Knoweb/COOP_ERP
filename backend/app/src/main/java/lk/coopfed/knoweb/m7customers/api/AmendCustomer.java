package lk.coopfed.knoweb.m7customers.api;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Amend a customer's names, language, attributes and tags (27A: UpdateAttributes / TagCustomer).
 * The whole set is sent: the names and language replace the current ones, {@code attributes}
 * replaces the attributes, {@code tags} is the complete list of the society's tags for them.
 */
public record AmendCustomer(
        UUID customerId,
        String displayName,
        String displayNameSi,
        String displayNameTa,
        String language,
        Map<String, String> attributes,
        List<String> tags) {

    public AmendCustomer {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
