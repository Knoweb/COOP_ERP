package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/**
 * ReceiveTransfer (25A section 6.3; doc 25 flow 6.6): the destination receives what the source
 * sent, in full (a short receipt and its ADJ draft are deferred for the demo).
 */
public record ReceiveTransfer(UUID transferId) {}
