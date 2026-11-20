package lk.coopfed.knoweb.m3pricing.api;

import java.util.List;
import java.util.UUID;

/**
 * The outcome of a bulk price list import (CAT-13).
 */
public record PriceListImportReport(
        UUID priceListId,
        boolean saved,
        int totalRows,
        int importedLines,
        int errorRows,
        List<PriceListImportProblem> problems,
        List<SetLinesResult.Outcome> outcomes) {

    public record PriceListImportProblem(int line, String sku, String field, String message) {}
}
