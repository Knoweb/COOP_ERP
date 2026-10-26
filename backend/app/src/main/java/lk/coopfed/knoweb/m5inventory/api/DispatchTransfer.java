package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** DispatchTransfer (25A section 6.3): mark an issued transfer as having left its source. */
public record DispatchTransfer(UUID transferId) {}
