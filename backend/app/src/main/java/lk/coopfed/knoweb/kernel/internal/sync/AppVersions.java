package lk.coopfed.knoweb.kernel.internal.sync;

/**
 * Compares till application versions against the floor (doc 32 section 3.3 step 1; doc 31). A
 * version is dotted numbers, "1.4.2"; anything after the numbers ("1.4.2-rc1", "1.4.2+build7")
 * is ignored, and a missing part counts as zero, so "1.4" equals "1.4.0".
 */
final class AppVersions {

    private AppVersions() {}

    /** Whether {@code version} is at or above {@code floor}; a version that is not dotted numbers is below any floor above 0. */
    static boolean atLeast(String version, String floor) {
        return compare(version, floor) >= 0;
    }

    static int compare(String left, String right) {
        long[] a = parts(left);
        long[] b = parts(right);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            long x = i < a.length ? a[i] : 0;
            long y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    private static long[] parts(String version) {
        if (version == null) {
            return new long[0];
        }
        String numbers = version.strip().split("[^0-9.]", 2)[0];
        if (numbers.isEmpty()) {
            return new long[0];
        }
        String[] pieces = numbers.split("\\.");
        long[] result = new long[pieces.length];
        for (int i = 0; i < pieces.length; i++) {
            try {
                result[i] = pieces[i].isEmpty() ? 0 : Long.parseLong(pieces[i]);
            } catch (NumberFormatException tooLong) {
                result[i] = Long.MAX_VALUE;
            }
        }
        return result;
    }
}
