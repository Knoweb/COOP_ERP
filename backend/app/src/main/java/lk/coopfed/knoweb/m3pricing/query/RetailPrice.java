package lk.coopfed.knoweb.m3pricing.query;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * ResolveRetailPrice (doc 23 section 5.2): the engine's answer for one line at a shop, steps 1 to 4
 * of doc 23 section 3.5 (list price, batch term under the MRP policy, control term, the lowest of
 * them). Rules are not applied here: this is the shelf price, what the snapshot will carry to the
 * till (M3-09), not a receipt.
 *
 * @param sellable        false when the society's published retail list has no line for the SKU and
 *                        unit on the date, or the policy is PICKER and the cashier must pick a batch
 * @param reason          why not sellable: price.no_list_line or price.needs_pick (engine ids)
 * @param listPrice       the retail list line's price, tax-inclusive; null when there is none
 * @param unitPrice       min(list, batch term, control term)
 * @param mrpApplied      the batch term: the printed MRP the policy chose; null when none
 * @param controlPrice    the control price in force for the SKU and unit; null when none
 * @param capReason       which bound set the price: NONE, MRP_LOWEST, MRP_BARCODE, MRP_PICKED, CONTROL_PRICE
 * @param policy          the effective MRP policy applied (AUTO_LOWEST, BARCODE_RESOLVED, PICKER)
 * @param batchId         the batch whose MRP bound the price, when one did
 */
public record RetailPrice(
        UUID locationId,
        UUID skuId,
        String uomCode,
        boolean sellable,
        String reason,
        UUID priceListId,
        BigDecimal listPrice,
        BigDecimal unitPrice,
        BigDecimal mrpApplied,
        BigDecimal controlPrice,
        String capReason,
        String policy,
        UUID batchId,
        String engineVersion) {}
