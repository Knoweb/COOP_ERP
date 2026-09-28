package lk.coopfed.knoweb.testsupport;

/**
 * How many times a randomised property test repeats itself. A pull request runs a smaller
 * count so the integration job stays fast; a push to main and the nightly run use the full
 * count named by each test, because that is where a rare interleaving is worth the extra
 * minutes. Set with the system property {@code coop-erp.test.property-tries} (ci.yml passes
 * it from the environment variable {@code COOP_ERP_PROPERTY_TRIES}); a test with no value set
 * runs its own full count.
 */
public final class PropertyTestTuning {

    private static final String PROPERTY = "coop-erp.test.property-tries";

    private PropertyTestTuning() {}

    /**
     * @param full the count this test runs when nothing overrides it (main, nightly, a laptop)
     * @return the count to run this time
     */
    public static int tries(int full) {
        String configured = System.getProperty(PROPERTY);
        if (configured == null || configured.isBlank()) {
            return full;
        }
        int requested = Integer.parseInt(configured.trim());
        return Math.min(requested, full);
    }
}
