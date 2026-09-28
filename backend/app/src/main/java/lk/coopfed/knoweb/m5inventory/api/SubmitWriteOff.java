package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** SubmitWriteOff (25A section 6.3): the draft is issued as a WOF document and waits for its witness. */
public record SubmitWriteOff(UUID writeOffId) {}
