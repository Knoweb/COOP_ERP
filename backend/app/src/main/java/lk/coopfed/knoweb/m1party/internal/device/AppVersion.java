package lk.coopfed.knoweb.m1party.internal.device;

import java.util.regex.Pattern;

/**
 * A till release number as the update service writes it (doc 31): dot-separated whole numbers,
 * "1.4.2". Compared number by number, so 1.10 is above 1.9, and a missing part counts as zero,
 * so 1.4 equals 1.4.0.
 */
final class AppVersion {

    /** The same pattern as the slice's AppVersion schema (openapi/m1party.yaml). */
    static final Pattern FORMAT = Pattern.compile("^[0-9]{1,5}(\\.[0-9]{1,5}){0,3}$");

    private AppVersion() {}

    static boolean isWellFormed(String version) {
        return version != null && FORMAT.matcher(version).matches();
    }

    /** Whether {@code version} is at or above {@code floor}; both must be well formed. */
    static boolean isAtLeast(String version, String floor) {
        return compare(version, floor) >= 0;
    }

    static int compare(String left, String right) {
        if (!isWellFormed(left) || !isWellFormed(right)) {
            throw new IllegalArgumentException("Not a release number: " + left + " / " + right);
        }
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }
}
