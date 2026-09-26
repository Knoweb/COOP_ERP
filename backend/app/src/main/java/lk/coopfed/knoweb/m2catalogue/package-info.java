/**
 * M2 Catalogue & Batch. Scaffolded from the hello module; its implementation guide says what to
 * build, and README.md in this package is the living guide once code exists.
 *
 * <p>allowedDependencies lists every module package this one may use (17A section 4.2). 22A
 * section 4 names the kernel, "m1party::api", "m1party::query", "m3pricing::query" and
 * "m5inventory::query"; a name resolves only once that package exists and carries a
 * NamedInterface, so the list holds the ones that do today and the README says which are
 * still to come. The architecture tests fail on anything else.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M2 Catalogue & Batch",
        allowedDependencies = {"kernel", "kernel::api", "m1party::query"})
package lk.coopfed.knoweb.m2catalogue;
