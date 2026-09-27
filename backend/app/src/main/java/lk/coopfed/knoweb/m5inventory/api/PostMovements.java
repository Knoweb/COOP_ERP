package lk.coopfed.knoweb.m5inventory.api;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The internal command of the ledger (25A section 6.1, "LedgerService.post(movements, doc, ctx)"):
 * the movements one document causes, posted together. Every receipt, sale, count, write-off,
 * repack, transfer and opening balance is a caller that assembles movements and cites its
 * document; nothing else changes stock.
 *
 * @param documentId    the document every movement cites (doc 18 B-I5)
 * @param occurredAt    when it happened; the ledger's own clock when null
 * @param occurredLocal what the till's clock showed, for a till fact; null otherwise
 * @param movements     at least one, applied in this order
 */
public record PostMovements(
        UUID documentId, Instant occurredAt, LocalDateTime occurredLocal, List<Movement> movements) {}
