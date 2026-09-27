/**
 * M8 Reporting Pipeline: the read side (28A). Projections built from the events of the other
 * modules, the report definitions and their outputs (screen, CSV, A4), and the dashboard.
 * README.md in this package is the living guide.
 *
 * <p>allowedDependencies (28A section 4): the kernel, and M1's and M2's query packages for the
 * names a report shows. M8 is the read side: it calls no module's api and reads no module's
 * tables; everything it knows arrived as an event.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M8 Reporting Pipeline",
        allowedDependencies = {"kernel", "kernel::api", "m1party::query", "m2catalogue::query"})
package lk.coopfed.knoweb.m8reporting;
