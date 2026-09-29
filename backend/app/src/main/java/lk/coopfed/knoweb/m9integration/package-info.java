/**
 * M9 Integration (29A): turns the other modules' facts into the outside world's shapes. Built so
 * far: the accounting export (the postings of journal.postings_ready.v1, the journal export as a
 * CSV file per entity and period, its reconciliation) and the notification side of the kernel's
 * dispatcher (templates, rules, the audience of contacts, the e-mail and SMS adapters). README.md
 * in this package is the living guide.
 *
 * <p>allowedDependencies: the kernel's api only. M9 reads the other modules' facts from their
 * events, by field name, and decides nothing about money (29A, "Read this first").
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M9 Integration",
        allowedDependencies = {"kernel::api"})
package lk.coopfed.knoweb.m9integration;
