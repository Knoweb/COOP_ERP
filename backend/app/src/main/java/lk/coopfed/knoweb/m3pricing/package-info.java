/**
 * M3 Pricing & Rules (doc 23; 23A). README.md in this package is the living guide.
 *
 * <p>allowedDependencies: the kernel; the shared engine (central calls the same functions the
 * till runs, 23A section 6); and, of the modules 23A section 4 names, those this code uses
 * today: M1's api (TradePriceListCheck, which M3 answers) and query (relationships), M2's query
 * (SKUs and batches). M5's query arrives with the retail ceiling check (M3-06).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M3 Pricing & Rules",
        allowedDependencies = {"kernel", "kernel::api", "engine", "m1party::api", "m1party::query", "m2catalogue::query"
        })
package lk.coopfed.knoweb.m3pricing;
