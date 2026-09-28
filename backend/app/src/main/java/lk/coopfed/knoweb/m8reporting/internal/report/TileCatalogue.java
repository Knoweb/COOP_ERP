package lk.coopfed.knoweb.m8reporting.internal.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.Messages;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * The dashboard tiles as data (28A section 3, dashboard_tile): read once at start from
 * {@code seed/m8reporting/dashboard-tiles.yaml} and checked by {@link DefinitionValidator}
 * against the report catalogue (a tile's drill report must exist). Each tile names its source, a
 * named query of {@link TileSources}.
 */
@Component
class TileCatalogue {

    static final String RESOURCE = "seed/m8reporting/dashboard-tiles.yaml";

    /** What a tile may open besides a report: the exception queue. */
    static final String EXCEPTIONS = "exceptions";

    static final Set<String> KINDS = Set.of(ReportCatalogue.COUNT, ReportCatalogue.MONEY, ReportCatalogue.PERCENT);

    /**
     * One tile.
     *
     * @param source the named query of {@link TileSources}
     * @param drill  the report it opens, "exceptions", or null
     * @param trend  it also answers the eight weeks ending today
     * @param cost   a cost figure: only for the owner's users and the Federation view
     */
    record TileDefinition(
            String tileId, String labelId, String kind, String source, String drill, boolean trend, boolean cost) {}

    private final List<TileDefinition> all;

    @org.springframework.beans.factory.annotation.Autowired
    TileCatalogue(ReportCatalogue reports, Messages messages) {
        this(read(RESOURCE), reports, messages);
    }

    TileCatalogue(List<TileDefinition> tiles, ReportCatalogue reports, Messages messages) {
        List<String> faults = DefinitionValidator.tiles(tiles, reports, messages);
        if (!faults.isEmpty()) {
            throw new IllegalStateException("The dashboard tiles are not valid: " + String.join("; ", faults));
        }
        this.all = List.copyOf(tiles);
    }

    List<TileDefinition> all() {
        return all;
    }

    @SuppressWarnings("unchecked")
    static List<TileDefinition> read(String resource) {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        List<TileDefinition> tiles = new ArrayList<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) root.getOrDefault("tiles", List.of())) {
            Object drill = entry.get("drill");
            tiles.add(new TileDefinition(
                    String.valueOf(entry.get("id")),
                    String.valueOf(entry.get("label")),
                    String.valueOf(entry.get("kind")),
                    String.valueOf(entry.get("source")),
                    drill == null ? null : String.valueOf(drill),
                    Boolean.TRUE.equals(entry.get("trend")),
                    Boolean.TRUE.equals(entry.get("cost"))));
        }
        return tiles;
    }
}
