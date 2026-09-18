package lk.coopfed.knoweb.kernel.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A typed link from a later, correcting document to its original
 * (doc 18 §5, table {@code document_link}). {@code amount} is set for SETTLES and
 * CREDITS: the amount applied to that specific original.
 */
public record DocumentLinkRecord(
        UUID fromDocumentId,
        UUID toDocumentId,
        LinkType linkType,
        BigDecimal amount,
        Instant createdAt,
        UUID createdBy) {
}
