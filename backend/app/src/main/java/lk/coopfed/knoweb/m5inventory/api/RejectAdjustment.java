package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** RejectAdjustment (25A section 6.3): the variances beyond tolerance are not posted; the count closes. */
public record RejectAdjustment(UUID taskId, String reason) {}
