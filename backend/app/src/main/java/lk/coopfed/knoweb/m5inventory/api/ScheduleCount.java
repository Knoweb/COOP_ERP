package lk.coopfed.knoweb.m5inventory.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * ScheduleCount (25A section 6.3; doc 25 section 3.5): a count of one location, of everything
 * (FULL) or of some items (SKUS), on a day.
 *
 * @param scopeKind FULL or SKUS
 * @param skuIds    the items counted when SKUS; ignored for FULL
 */
public record ScheduleCount(UUID locationId, String scopeKind, List<UUID> skuIds, LocalDate scheduledFor) {}
