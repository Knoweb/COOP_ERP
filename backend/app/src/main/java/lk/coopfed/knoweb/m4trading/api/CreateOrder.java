package lk.coopfed.knoweb.m4trading.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * CreateOrder (24A section 6): the buyer, in its entity-wide scope, drafts an order to a seller it
 * trades with. The relationship is the ACTIVE one of the pair today (M1); each line names an
 * active item, a unit it is sold in and a positive quantity. The draft takes no number.
 *
 * @param deliverToLocationId where the buyer wants the goods (M4-11, CR-24A-2): one of its own
 *     locations, or null. The seller's delivery note takes each drop's ship-to from it.
 */
public record CreateOrder(
        UUID sellerEntityId, LocalDate requestedEta, String notes, List<Line> lines, UUID deliverToLocationId) {

    /** An order with no delivery location named (the callers written before M4-11). */
    public CreateOrder(UUID sellerEntityId, LocalDate requestedEta, String notes, List<Line> lines) {
        this(sellerEntityId, requestedEta, notes, lines, null);
    }

    public record Line(UUID skuId, String uomCode, BigDecimal qty) {}
}
