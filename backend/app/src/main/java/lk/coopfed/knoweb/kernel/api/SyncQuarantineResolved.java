package lk.coopfed.knoweb.kernel.api;

import java.util.UUID;

/**
 * An administrator resolved a quarantined till event (wave 2, CR-32-1 item 2; doc 32 sections 3.3
 * step 6 and 7): REPAIRED, the till resent a correct copy, or DISCARDED, nothing will. The row
 * stays as the record that a fact was refused; its raw event goes after a retention. An audit
 * record ({@code SYNC_QUARANTINE_RESOLVED}) stands beside it with the reason; no reason text
 * travels here.
 *
 * @param reason     why it was quarantined (SCHEMA, DUPLICATE_ID, HASH, TOO_LARGE, FORBIDDEN_FIELD)
 * @param resolution REPAIRED or DISCARDED
 */
public record SyncQuarantineResolved(
        UUID quarantineId, UUID deviceId, long deviceSeq, String reason, String resolution, String reasonCode)
        implements DomainEvent {

    public static final String TYPE = "sync.quarantine.resolved.v1";
}
