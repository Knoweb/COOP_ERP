package lk.coopfed.knoweb.m5inventory.internal.consumers;

import java.util.List;
import java.util.UUID;
import lk.coopfed.knoweb.m5inventory.api.IssueTransfer;

/**
 * An approved transfer request of which nothing could be sent (wave 3, M1M2M3M5-17), from {@link
 * TransferRequestConsumer}.
 *
 * @param shortfalls each requested item, wanted and sent (zero)
 */
record RecordTransferRequestUnfilled(
        UUID transferRequestId, UUID fromLocationId, UUID toLocationId, List<IssueTransfer.Shortfall> shortfalls) {}
