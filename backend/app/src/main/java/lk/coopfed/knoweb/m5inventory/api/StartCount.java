package lk.coopfed.knoweb.m5inventory.api;

import java.util.UUID;

/** StartCount (25A section 6.3): the count begins; what the book says of every lot in scope is noted. */
public record StartCount(UUID taskId) {}
