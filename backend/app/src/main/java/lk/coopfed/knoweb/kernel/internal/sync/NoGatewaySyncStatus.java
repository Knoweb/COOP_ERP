package lk.coopfed.knoweb.kernel.internal.sync;

import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.SyncStatus;
import org.springframework.stereotype.Component;

/**
 * {@link SyncStatus} until the sync gateway exists (19A K-08). Without the gateway there is no
 * device cursor, so central cannot show that any device is drained, and it says so: every
 * answer is false. A counter transfer therefore needs the loss recorded until K-08 replaces
 * this class with one that reads {@code device_sync_cursor}; that is the safe side (doc 32
 * section 8: "the old device's outbox is fully acknowledged, or its loss is recorded").
 */
@Component
class NoGatewaySyncStatus implements SyncStatus {

    @Override
    public boolean drained(UUID deviceId) {
        return false;
    }
}
