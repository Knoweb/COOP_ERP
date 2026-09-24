package lk.coopfed.knoweb.m1party.api;

/**
 * Registers many entities from one CSV file (21A section 5, {@code POST /v1/party/bulk-register};
 * section 8, the "Bulk upload" button of the society register). The file is UTF-8 text with a
 * header row; the columns are the fields of {@link RegisterEntity} in snake case
 * ({@code entity_code, entity_type, legal_name_en, legal_name_si, legal_name_ta, registration_no,
 * vat_registration_no, district, default_language, financial_year_start_month}).
 *
 * <p>The answer is a {@link ValidationReport}. Every row is validated first; when any row has a
 * problem, nothing is registered and the report says which rows and why, so that the file is
 * corrected and sent again. When every row passes, all are registered in one transaction.
 */
public record BulkRegisterEntities(String csv) {}
