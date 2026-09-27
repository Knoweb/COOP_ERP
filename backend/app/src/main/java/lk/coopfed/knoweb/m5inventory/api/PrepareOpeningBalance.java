package lk.coopfed.knoweb.m5inventory.api;

import java.util.List;
import java.util.UUID;

/** PrepareOpeningBalance (25A section 6.3; doc 25 section 4.7): the counted stock of one location. */
public record PrepareOpeningBalance(UUID locationId, List<OpeningBalanceLine> lines) {}
