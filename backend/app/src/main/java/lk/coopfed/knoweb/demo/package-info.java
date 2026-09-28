/**
 * The demo data loader (DEMO-01, docs/DEMO.md): sets up a believable demonstration for
 * cooperative staff through the command handlers of the modules, never through SQL on their
 * business tables, so that audit, events, ledgers and numbering stay what they would be had a
 * person done it on the screens. It uses the published api and query packages only, like any
 * other caller. It is not a business module: nothing depends on it, and it runs only when
 * {@code coop-erp.demo.load} is true ({@code make demo-data}).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Demo data",
        allowedDependencies = {
            "kernel",
            "kernel::api",
            "m1party::api",
            "m1party::query",
            "m2catalogue::api",
            "m2catalogue::query",
            "m3pricing::api",
            "m3pricing::query",
            "m4trading::api",
            "m4trading::query",
            "m5inventory::api",
            "m5inventory::query"
        })
package lk.coopfed.knoweb.demo;
