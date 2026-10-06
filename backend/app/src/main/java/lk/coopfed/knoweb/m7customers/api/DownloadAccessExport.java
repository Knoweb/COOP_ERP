package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/**
 * Hand over the export of a fulfilled ACCESS request (wave 2, M7CR-11; CR-27A-1 item 4): a command,
 * not a read, because doc 27 section 9.3 wants every read of a customer record outside the sales
 * path audited. The responsible officer only. The handler answers the export itself.
 */
public record DownloadAccessExport(UUID requestId) {}
