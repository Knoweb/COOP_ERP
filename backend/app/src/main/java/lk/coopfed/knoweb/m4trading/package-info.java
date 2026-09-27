/**
 * M4 Trading Documents: the inter-entity flow from order to settlement, recording and linking
 * money and never moving it (doc 24). README.md in this package is the living guide.
 *
 * <p>allowedDependencies lists every module package this one may use (17A section 4.2). 24A
 * section 4 names the kernel, "m1party::api", "m1party::query", "m2catalogue::api",
 * "m2catalogue::query", "m3pricing::query" and "m5inventory::query". M3's query answers the
 * price since M4-04; M5's does not exist yet (the M5 lane is building it), so M4 asks its price, tax and availability
 * questions through interfaces of its own api package ({@code TradePricing}, {@code TaxRates},
 * {@code InventoryAvailability}), answered for the demo by {@code internal.integration}. When
 * the real module lands, it implements the interface or M4 switches to its query and deletes the
 * demo answer, as M1 did with the M3 price-list stub.
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
            "m3pricing::query"
        })
package lk.coopfed.knoweb.m4trading;
