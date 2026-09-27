package lk.coopfed.knoweb.m4trading.api;

import java.util.UUID;

/** ConfirmGrn (24A section 6.1), the pivot: ownership of the goods passes to the receiver. */
public record ConfirmGrn(UUID grnId) {}
