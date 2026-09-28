package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** AcknowledgeNegativeLot (25A section 6.3; doc 25 flow 6.2): somebody looked at a lot below zero. */
public record AcknowledgeNegativeLot(UUID stockLotId, String note) {}
