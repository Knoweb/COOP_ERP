package lk.coopfed.knoweb.testsupport;

/**
 * Whether this test run is the scaffold proof ({@code make test-scaffold}). The proof deletes the
 * built M9 ({@code tools/scaffold-proof-prepare.mjs}) and re-creates the name {@code m9integration}
 * from hello, so for the length of the proof the {@code integration} schema holds the copy's
 * tables, not M9's. The few cross-module tests that pin the built M9 (its migration numbers, its
 * notification tables) ask this and leave M9 out while the copy stands in for it.
 *
 * <p>Set only by the Makefile's {@code test-scaffold} target, through the environment variable
 * {@code COOP_ERP_SCAFFOLD_PROOF=true}, which build.gradle.kts passes to the test JVMs as the
 * system property {@code coop-erp.test.scaffold-proof}. Never set on main, a pull request or a
 * laptop's {@code make test-int}, so there the built M9 is checked in full.
 */
public final class ScaffoldProof {

    private static final String PROPERTY = "coop-erp.test.scaffold-proof";

    private ScaffoldProof() {}

    /** True only while the scaffold proof's copy stands in for the built M9. */
    public static boolean active() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false").trim());
    }
}
