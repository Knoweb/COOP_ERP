package lk.coopfed.knoweb.kernel.api;

import java.time.Instant;
import java.util.UUID;

/** An administrator issued a one-time enrolment code for a device; the code is never in the event. */
public record EnrolmentCodeIssued(UUID enrolmentCodeId, UUID deviceId, Instant expiresAt) implements DomainEvent {

    public static final String TYPE = "device.enrolment_code_issued.v1";
}
