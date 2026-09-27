package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CreateOrder (24A section 6): the buyer, in its entity-wide scope, drafts an order to a seller it
 * trades with. The relationship is the ACTIVE one of the pair today (M1); each line names an
 * active item, a unit it is sold in and a positive quantity. The draft takes no number.
 */
public record CreateOrder(UUID sellerEntityId, LocalDate requestedEta, String notes, List<Line> lines) {

    public record Line(UUID skuId, String uomCode, BigDecimal qty) {}
}
