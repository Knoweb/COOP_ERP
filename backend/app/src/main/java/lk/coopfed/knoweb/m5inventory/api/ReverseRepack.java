package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** ReverseRepack (25A section 6.3): while the output is untouched, the input lot is restored. */
public record ReverseRepack(UUID repackId, String reason) {}
