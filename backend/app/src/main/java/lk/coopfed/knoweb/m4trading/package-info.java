/**
 * M4 Trading Documents: the inter-entity flow from order to settlement, recording and linking
 * money and never moving it (doc 24). README.md in this package is the living guide.
 *
 * <p>allowedDependencies lists every module package this one may use (17A section 4.2). 24A
 * section 4 names the kernel, "m1party::api", "m1party::query", "m2catalogue::api",
 * "m2catalogue::query", "m3pricing::query" and "m5inventory::query". M3's query answers the
 * price since M4-04 and M5's the availability since M4-05, through interfaces of M4's own api
 * package ({@code TradePricing}, {@code InventoryAvailability}) answered by
 * {@code internal.integration}; the VAT rate in force comes straight from M2's
 * {@code CatalogueQueries.taxRateInForce} (28 Sep 2026).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M4 Trading Documents",
        allowedDependencies = {
            "kernel",
            "kernel::api",
            "m1party::api",
            "m1party::query",
            "m2catalogue::api",
            "m2catalogue::query",
            "m3pricing::query",
            "m5inventory::query"
        })
package lk.coopfed.knoweb.m4trading;
