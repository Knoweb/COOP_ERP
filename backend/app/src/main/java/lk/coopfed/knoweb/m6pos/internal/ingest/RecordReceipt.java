package lk.coopfed.knoweb.m6pos.internal.ingest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A receipt a till issued, as its bundle {@code receipt.issued.v1} carries it (doc 32 section
 * 3.1; the document with doc 18's column names, its lines and tenders), read by
 * {@link PosIngestConsumer}.
 */
record RecordReceipt(
        UUID documentId,
        UUID locationId,
        UUID tillPositionId,
        UUID deviceId,
        UUID sessionId,
        UUID seriesId,
        Long docNumber,
        String docNumberDisplay,
        Instant issuedAt,
        LocalDate businessDate,
        UUID operatorUserId,
        String currency,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal grossAmount,
        String contentHash,
        Long deviceSeq,
        List<Line> lines,
        List<Tender> tenders) {

    record Line(
            int lineNo,
            UUID skuId,
            UUID batchId,
            String uomCode,
            BigDecimal qty,
            BigDecimal unitPrice,
            BigDecimal lineTotal) {}

    record Tender(int seq, String kind, BigDecimal amount) {}
}
