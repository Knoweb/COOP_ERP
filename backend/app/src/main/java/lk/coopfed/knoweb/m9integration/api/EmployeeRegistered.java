package lk.coopfed.knoweb.m9integration.api;

import java.time.Instant;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/**
 * integration.employee_registered.v1: an authoritative employee record was registered or updated.
 */
public record EmployeeRegistered(
        UUID employeeId, String employeeNo, UUID ownerEntityId, UUID locationId, Instant registeredAt)
        implements DomainEvent {

    public static final String TYPE = "integration.employee_registered.v1";
}
