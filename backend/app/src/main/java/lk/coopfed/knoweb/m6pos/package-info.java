/**
 * M6 Point of Sale, the central side (26A section 10), minimal for the demo: what a till's
 * uploaded sessions and receipts become at central. README.md in this package is the living
 * guide. The till itself is the till track's (till/).
 *
 * <p>allowedDependencies: 26A section 10 names the kernel and the query packages of M1, M2, M3
 * and M5; the list holds what the code uses, the kernel's api only.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M6 Point of Sale",
        allowedDependencies = {"kernel", "kernel::api"})
package lk.coopfed.knoweb.m6pos;
