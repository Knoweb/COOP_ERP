package lk.coopfed.knoweb.kernel.internal.sync;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The manifest of a snapshot and the hash of each of its tables (doc 32 section 5.2: "verifies
 * hashes"; section 9: "snapshot manifest hashes"). The till recomputes every table's hash from
 * what it received, compares it with the manifest and checks the manifest's signature before it
 * swaps the snapshot in; a table that does not match is not applied at all.
 *
 * <p>The rules are in the slice (openapi/sync.yaml, SnapshotDelta.manifest) because the till
 * implements them in Kotlin: a table's lines, one per row, the upserts sorted by row id and then
 * the tombstones sorted by row id,
 *
 * <pre>
 *   U &lt;row_id&gt; &lt;apply_from or -&gt; &lt;data as JSON, keys sorted, no whitespace&gt;
 *   D &lt;row_id&gt; &lt;apply_from or -&gt;
 * </pre>
 *
 * joined by a newline and hashed with SHA-256 (hex). The manifest itself travels as the exact
 * text that was signed, so the till never has to rebuild it.
 */
final class SnapshotManifest {

    /** One row to insert or replace, with the business date from which it applies (null: at once). */
    record Row(UUID rowId, LocalDate applyFrom, Map<String, Object> data) {}

    /** One row to remove. */
    record Tombstone(UUID rowId, LocalDate applyFrom) {}

    /** One snapshot table of the answer. */
    record Table(List<Row> upserts, List<Tombstone> tombstones) {}

    /** Keys sorted at every level, no whitespace, decimals never in exponent form. */
    static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN, true)
            .build();

    private SnapshotManifest() {}

    /** The manifest text that is signed: location, versions and, per table, counts and hash. */
    static String manifest(UUID location, long since, long version, boolean full, Map<String, Table> tables) {
        Map<String, Object> perTable = new TreeMap<>();
        tables.forEach((name, table) -> {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("upserts", table.upserts().size());
            entry.put("tombstones", table.tombstones().size());
            entry.put("sha256", tableHash(table));
            perTable.put(name, entry);
        });
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("full", full);
        manifest.put("location_id", location.toString());
        manifest.put("since", since);
        manifest.put("tables", perTable);
        manifest.put("version", version);
        return canonical(manifest);
    }

    /** The table's hash by the rule of the slice. */
    static String tableHash(Table table) {
        List<String> lines = new ArrayList<>();
        table.upserts().stream()
                .sorted(Comparator.comparing((Row row) -> row.rowId().toString()))
                .forEach(row ->
                        lines.add("U " + row.rowId() + " " + date(row.applyFrom()) + " " + canonical(row.data())));
        table.tombstones().stream()
                .sorted(Comparator.comparing((Tombstone gone) -> gone.rowId().toString()))
                .forEach(gone -> lines.add("D " + gone.rowId() + " " + date(gone.applyFrom())));
        return sha256(String.join("\n", lines));
    }

    static String canonical(Object value) {
        try {
            return CANONICAL.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("A snapshot row could not be written as JSON", e);
        }
    }

    private static String date(LocalDate date) {
        return date == null ? "-" : date.toString();
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
