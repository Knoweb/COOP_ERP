package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** WitnessWriteOff (25A section 6.3): a person other than the requester confirms the loss. */
public record WitnessWriteOff(UUID writeOffId) {}
