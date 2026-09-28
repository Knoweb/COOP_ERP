/**
 * M7 Customers & Payment Recording (27A), the back office part: the society's members, their
 * credit accounts, the posting ledger fed by the till's account tenders, and repayments recorded
 * at the society office. README.md in this package is the living guide.
 *
 * <p>allowedDependencies: 27A section 4 names the kernel and M1's api and query; the list holds
 * what the code uses (the kernel's api and M1's query, for the entity code of the CPR series).
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "M7 Customers & Payment Recording",
        allowedDependencies = {"kernel", "kernel::api", "m1party::query"})
package lk.coopfed.knoweb.m7customers;
