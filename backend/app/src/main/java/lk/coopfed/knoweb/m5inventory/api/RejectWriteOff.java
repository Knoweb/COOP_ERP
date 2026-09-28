package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** RejectWriteOff (25A section 6.3): the write-off is refused with a reason; no stock moves. */
public record RejectWriteOff(UUID writeOffId, String reason) {}
