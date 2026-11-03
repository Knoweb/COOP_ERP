package lk.coopfed.knoweb.m4trading.api;

import java.util.List;
import java.util.UUID;

/** The result summary of an inter-entity receivable opening balance import (CAT-12). */
public record BulkReceivableReport(
        UUID importId,
        String status,
        int totalRows,
        int importedCount,
        List<UUID> documentIds,
        List<ReceivableProblem> problems) {

    public static final String IMPORTED = "IMPORTED";
    public static final String REJECTED = "REJECTED";
}
