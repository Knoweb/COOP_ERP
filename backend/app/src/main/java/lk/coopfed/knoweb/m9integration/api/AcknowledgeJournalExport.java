package lk.coopfed.knoweb.m9integration.api;

import java.util.UUID;

/** ACC-03: acknowledge receipt of a generated journal export. */
public record AcknowledgeJournalExport(UUID exportId) {}
