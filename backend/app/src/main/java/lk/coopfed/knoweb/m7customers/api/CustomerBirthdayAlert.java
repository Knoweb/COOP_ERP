package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * customer.birthday_alert.v1: A member/customer birthday alert notification fact (PGL-M3-02).
 *
 * <p>Emitted for downstream routing by Member 3's Central Notification Gateway in M9.
 * Carries no PII in field names or values (strictly adhering to OutboxWriter and AGENTS.md rules).
 */
public record CustomerBirthdayAlert(
        UUID customerId, UUID ownerEntityId, UUID locationId, String customerRef, int greetingYear)
        implements DomainEvent {

    public static final String TYPE = "customer.birthday_alert.v1";
}
