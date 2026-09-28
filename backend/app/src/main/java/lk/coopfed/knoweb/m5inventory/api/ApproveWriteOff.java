package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** ApproveWriteOff (25A section 6.3): the loss is approved within the approver's band and posted. */
public record ApproveWriteOff(UUID writeOffId) {}
