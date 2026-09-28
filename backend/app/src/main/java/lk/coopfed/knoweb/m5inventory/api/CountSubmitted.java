package lk.coopfed.knoweb.m5inventory.api;

import java.math.BigDecimal;
import java.util.UUID;
import lk.coopfed.knoweb.kernel.api.DomainEvent;

/** A count was submitted: {@code postedLines} variances within tolerance were posted, {@code reviewLines} wait for approval, worth {@code reviewValue}. */
public record CountSubmitted(
        UUID taskId,
        UUID ownerEntityId,
        UUID locationId,
        int lines,
        int postedLines,
        int reviewLines,
        BigDecimal reviewValue,
        Integer reviewBand)
        implements DomainEvent {

    public static final String TYPE = "count.submitted.v1";
}
