/**
 * M5 Inventory, Costing, Repack & Loss: where stock is and what it cost, by location, batch and
 * condition (25A). README.md in this package is the living guide.
 *
 * <p>allowedDependencies lists every module package this one may use (17A section 4.2). 25A
 * section 4 names the kernel, "m1party::api", "m1party::query", "m2catalogue::api" and
 * "m2catalogue::query"; the list holds the ones the code uses: locations are read through M1's
 * query package and batches through M2's.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M5 Inventory, Costing, Repack & Loss",
        allowedDependencies = {"kernel", "kernel::api", "m1party::query", "m2catalogue::query"})
package lk.coopfed.knoweb.m5inventory;
