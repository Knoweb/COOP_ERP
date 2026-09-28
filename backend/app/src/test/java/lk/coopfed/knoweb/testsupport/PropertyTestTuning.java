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

    /**
     * A floor a property test asserts over its full count, scaled to the count run this time,
     * so a shorter pull request run still asks for the same share of outcomes (rounded down).
     *
     * @param floor the floor at the full count
     * @param full the full count
     * @param tries the count run this time
     */
    public static int scaled(int floor, int full, int tries) {
        return floor * tries / full;
    }
}
