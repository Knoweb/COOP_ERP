package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;

/** One drop of a delivery note (doc 24 section 3.2): where it goes, who is billed, what it carries. */
public record DropSummary(
        UUID dropId,
        int seq,
        UUID shipToLocationId,
        UUID billToEntityId,
        List<UUID> orderIds,
        List<DeliveryLineSummary> lines) {}
