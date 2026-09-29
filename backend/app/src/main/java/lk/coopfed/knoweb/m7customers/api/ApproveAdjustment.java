package lk.coopfed.knoweb.m7customers.api;

import java.util.UUID;

/** ApproveAdjustment (27A section 6): another person than the requester posts the adjustment. */
public record ApproveAdjustment(UUID adjustmentId) {}
