package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** ApproveAdjustment (25A section 6.3): the variances of a count beyond tolerance are posted. */
public record ApproveAdjustment(UUID taskId) {}
