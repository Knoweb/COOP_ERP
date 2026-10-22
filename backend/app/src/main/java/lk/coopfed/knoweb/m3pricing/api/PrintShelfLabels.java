package lk.coopfed.knoweb.m3pricing.api;

import java.util.List;
import java.util.UUID;

/** Queue shelf labels for SKUs on the owner's retail list at one shop. */
public record PrintShelfLabels(UUID locationId, List<UUID> skuIds) {

    public PrintShelfLabels {
        skuIds = skuIds == null ? null : List.copyOf(skuIds);
    }
}
