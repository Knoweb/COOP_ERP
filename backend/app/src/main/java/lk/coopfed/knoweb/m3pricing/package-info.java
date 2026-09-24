/**
 * M3 Pricing & Rules. Scaffolded from the hello module; its implementation guide says what to
 * build, and README.md in this package is the living guide once code exists.
 *
 * <p>allowedDependencies lists every module package this one may use (17A section 4.2):
 * the kernel's api, then the api or query package of each module the guide names, for
 * example "m1party::api". The architecture tests fail on anything else.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M3 Pricing & Rules",
        allowedDependencies = {"kernel::api"})
package lk.coopfed.knoweb.m3pricing;
