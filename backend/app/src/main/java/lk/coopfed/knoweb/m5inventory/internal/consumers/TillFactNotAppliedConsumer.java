package lk.coopfed.knoweb.m5inventory.internal.consumers;

import com.fasterxml.jackson.databind.JsonNode;
import lk.coopfed.knoweb.kernel.api.EventConsumer;
import lk.coopfed.knoweb.kernel.api.Handles;
import lk.coopfed.knoweb.kernel.api.ScopeContext;
import org.springframework.stereotype.Component;

/**
 * The till's stock bundles M5 has no hook for yet (wave 2, M5-04; the kernel's sync gateway accepts
 * them: EventApplier.BUNDLE_TYPES): {@code count.recorded.v1}, {@code writeoff.requested.v1},
 * {@code repack.executed.v1} and {@code transfer.issued.v1}. Each is handed to {@link
 * RecordTillFactNotAppliedHandler}, which flags it, until its own hook replaces this consumer's
 * entry for that type.
 *
 * <p>Three of the types are also M5's own central events ({@code repack.executed.v1}, {@code
 * transfer.issued.v1}, {@code writeoff.requested.v1}): a fact a till uploaded carries its device on
 * the scope (K-08), an event central published does not, and only the till's is flagged.
 */
@Component
class TillFactNotAppliedConsumer {

    static final String CONSUMER = "m5.till-facts-not-applied";

    private final Handles<RecordTillFactNotApplied, Void> record;

    TillFactNotAppliedConsumer(Handles<RecordTillFactNotApplied, Void> record) {
        this.record = record;
    }

    static final String COUNT_RECORDED = "count.recorded.v1";
    static final String WRITEOFF_REQUESTED = "writeoff.requested.v1";
    static final String REPACK_EXECUTED = "repack.executed.v1";
    static final String TRANSFER_ISSUED = "transfer.issued.v1";

    @EventConsumer(types = COUNT_RECORDED, consumer = CONSUMER)
    public void onCountRecorded(JsonNode payload, ScopeContext scope) {
        flag(COUNT_RECORDED, payload, scope);
    }

    @EventConsumer(types = WRITEOFF_REQUESTED, consumer = CONSUMER)
    public void onWriteOffRequested(JsonNode payload, ScopeContext scope) {
        flag(WRITEOFF_REQUESTED, payload, scope);
    }

    @EventConsumer(types = REPACK_EXECUTED, consumer = CONSUMER)
    public void onRepackExecuted(JsonNode payload, ScopeContext scope) {
        flag(REPACK_EXECUTED, payload, scope);
    }

    @EventConsumer(types = TRANSFER_ISSUED, consumer = CONSUMER)
    public void onTransferIssued(JsonNode payload, ScopeContext scope) {
        flag(TRANSFER_ISSUED, payload, scope);
    }

    private void flag(String factType, JsonNode payload, ScopeContext scope) {
        if (scope == null || scope.deviceId() == null) {
            return;
        }
        record.handle(
                new RecordTillFactNotApplied(factType, Payloads.uuid(payload.path("document"), "document_id")), scope);
    }
}
