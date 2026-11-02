package lk.coopfed.knoweb.m2catalogue.api;

/**
 * A single row within a bulk supplier import sheet (PGL-M2-05).
 *
 * @param rowNumber 1-based index in the source payload/sheet
 * @param name supplier business name
 * @param supplierCode optional legacy supplier identifier
 */
public record SupplierImportRow(int rowNumber, String name, String supplierCode) {}
