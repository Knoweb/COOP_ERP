package lk.coopfed.knoweb.m7customers.api;

import java.util.List;
import java.util.Map;

/**
 * Register a customer at the caller's society (27A section 6, RegisterCustomer). The society is
 * the caller's scope entity, never a field.
 *
 * @param phone             as typed (07x xxx xxxx or +947x...); stored E.164
 * @param language          en, si or ta: the language of the customer's statements
 * @param consents          CREDIT_ACCOUNT (required) and STATEMENTS_NOTIFICATIONS
 * @param via               WEB or PAPER at the office (TILL when the till registers)
 * @param confirmedIdentity the officer confirmed that a recent holder of the phone is another
 *                          person (27A section 6.1, NEEDS_CONFIRMATION)
 */
public record RegisterCustomer(
        String displayName,
        String displayNameSi,
        String displayNameTa,
        String language,
        String phone,
        List<String> consents,
        String via,
        Map<String, String> attributes,
        List<String> tags,
        boolean confirmedIdentity) {

    public RegisterCustomer {
        consents = consents == null ? List.of() : List.copyOf(consents);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
