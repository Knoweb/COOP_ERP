package lk.coopfed.knoweb.kernel.internal.security;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import lk.coopfed.knoweb.kernel.internal.ScopeFilter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * What every OpenAPI slice says about its operations, read once at start: the
 * {@code x-permission} of each operation by method and path template, and the set of read
 * permissions (the {@code x-permission} of every GET a user may call). The slices are the
 * contract (17A section 3: "the slice is the source"); the kernel enforces a GET's permission
 * from here (CR-19A-9, {@link ReadPermissionInterceptor}), so no controller checks a read by
 * hand and no module can forget one.
 *
 * <p>The path template is the one the generated interface maps ({@code /v1/catalogue/skus/{skuId}}),
 * which Spring hands back on every request as the best matching pattern, so the lookup is a
 * map read, not a match. A GET of the two device operations of the sync slice is not a read a
 * user holds (their x-permission names the device principal, {@link ScopeFilter}), and
 * {@link #AUTHENTICATED} names no permission, so neither is in the read set.
 */
@Component
public class SliceOperations {

    /**
     * The one x-permission value that is not a code of the catalogue: any signed-in principal.
     * Allowed on a GET that answers with the caller's own facts and nothing else (the session);
     * tools/check-permissions.mjs refuses it anywhere else.
     */
    public static final String AUTHENTICATED = "authenticated";

    private static final String SLICES = "classpath*:openapi/*.yaml";
    private static final String SHARED = "common.yaml";

    /**
     * The slice mark that keeps a read from a FEDERATION_VIEW session (CR-18-2; wave 2, TWK-30):
     * {@code x-federation-view: false} on an operation. The Federation reads its members' trading,
     * stock, finance and reporting data; it does not read their staff administration or their
     * customers' personal data.
     */
    public static final String FEDERATION_VIEW_MARK = "x-federation-view";

    private final Map<String, String> permissionByOperation;
    private final Set<String> readPermissions;
    private final Set<String> federationViewReadPermissions;

    public SliceOperations() {
        this(loadSlices());
    }

    /** From the slices' documents, for a test that hands in a slice of its own. */
    SliceOperations(Map<String, Map<String, Object>> slices) {
        Map<String, String> permissions = new LinkedHashMap<>();
        Set<String> reads = new TreeSet<>();
        Set<String> withheldFromTheFederation = new TreeSet<>();
        slices.forEach((name, document) -> map(document.get("paths"))
                .forEach((path, item) -> map(item).forEach((method, value) -> {
                    Object permission = map(value).get("x-permission");
                    if (permission == null) {
                        return; // "parameters", "summary" ... at path level, or a slice the rules test refuses
                    }
                    String code = permission.toString().trim();
                    permissions.put(key(method, path), code);
                    if ("get".equalsIgnoreCase(method)
                            && !AUTHENTICATED.equals(code)
                            && !ScopeFilter.isDeviceOperation(path)) {
                        reads.add(code);
                        if (Boolean.FALSE.equals(map(value).get(FEDERATION_VIEW_MARK))) {
                            withheldFromTheFederation.add(code);
                        }
                    }
                })));
        this.permissionByOperation = Map.copyOf(permissions);
        this.readPermissions = Set.copyOf(reads);
        // A permission is a code, not an operation: when one GET under a code is marked, the code
        // leaves the Federation's set (fail closed), and a module that wants the Federation to keep
        // reading its other views under that code gives them a code of their own.
        Set<String> federation = new TreeSet<>(reads);
        federation.removeAll(withheldFromTheFederation);
        this.federationViewReadPermissions = Set.copyOf(federation);
    }

    /** The x-permission of the operation, or null when no slice describes it. */
    public String permissionOf(String method, String pathTemplate) {
        if (method == null || pathTemplate == null) {
            return null;
        }
        return permissionByOperation.get(key(method, pathTemplate));
    }

    /** Every permission a GET of any slice asks for (the user-held reads). */
    public Set<String> readPermissions() {
        return readPermissions;
    }

    /**
     * What a FEDERATION_VIEW caller holds: every read of every slice (doc 18 section 3.7; 21A
     * section 3's federation-view template carries no permission of its own) except the codes of
     * the operations marked {@code x-federation-view: false} (CR-18-2: staff administration and
     * customers' personal data are the entity's).
     */
    public Set<String> federationViewReadPermissions() {
        return federationViewReadPermissions;
    }

    private static String key(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    private static Map<String, Map<String, Object>> loadSlices() {
        Map<String, Map<String, Object>> slices = new HashMap<>();
        try {
            Resource[] found = new PathMatchingResourcePatternResolver().getResources(SLICES);
            for (Resource slice : found) {
                String name = slice.getFilename();
                if (name == null || SHARED.equals(name)) {
                    continue;
                }
                try (InputStream in = slice.getInputStream()) {
                    slices.put(name, map(new Yaml().load(in)));
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the OpenAPI slices (" + SLICES + ")", e);
        }
        if (slices.isEmpty()) {
            throw new IllegalStateException("No OpenAPI slice found under " + SLICES + ": nothing can be read");
        }
        return slices;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
