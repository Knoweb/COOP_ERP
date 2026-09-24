package lk.coopfed.knoweb.kernel.internal.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The payload of a domain event never carries personal or secret data (AGENTS.md; doc 18). The
 * field-name check is the one place that rule is enforced, so it must catch the names people
 * really use and let through the identifiers that merely contain a forbidden word.
 */
class OutboxWriterForbiddenFieldsTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "name",
                "customerName",
                "legal_name_en",
                "firstName",
                "surname",
                "phone",
                "phoneNumber",
                "mobile",
                "mobile_no",
                "telephone",
                "whatsapp",
                "email",
                "emailAddress",
                "contactEmail",
                "address",
                "streetAddress",
                "city",
                "nic",
                "nicNumber",
                "passportNo",
                "password",
                "pin",
                "pinHash",
                "otp",
                "token",
                "accessToken",
                "secret",
                "dob",
                "dateOfBirth",
                "birthDate"
            })
    void aFieldThatNamesPersonalOrSecretDataIsRefused(String field) {
        assertThat(OutboxWriter.isForbiddenField(field)).as(field).isTrue();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "entityId",
                "technicianId",
                "shippingId",
                "clinicId",
                "pinnedAt",
                "entityCode",
                "status",
                "quantity",
                "amount",
                "documentNo",
                "occurredAt",
                "locationId",
                "reasonCode",
                "engineVersion"
            })
    void anIdentifierThatMerelyContainsAForbiddenWordIsNotRefused(String field) {
        assertThat(OutboxWriter.isForbiddenField(field)).as(field).isFalse();
    }
}
