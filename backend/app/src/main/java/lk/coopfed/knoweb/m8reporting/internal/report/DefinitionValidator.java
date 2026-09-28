package lk.coopfed.knoweb.m8reporting.internal.report;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.m8reporting.internal.report.ReportCatalogue.Definition;
import lk.coopfed.knoweb.m8reporting.internal.report.TileCatalogue.TileDefinition;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;

/**
 * The checks a report definition and a dashboard tile must pass (28A section 7,
 * catalogue/DefinitionValidator), run when the catalogues are read at start and by the build
 * (ReportCatalogueTest). Each answers the list of faults, empty when the data is sound.
 *
 * <p>A definition: an id for the URL, unique; a source among the named queries of
 * {@link ReportSources} (the allow-list: no SQL in a definition, and so nothing outside
 * reporting.*); a period when the source reads one; only known parameters; at least one column,
 * each a key the source fills, once, with a known kind; the title, the decision and every column
 * label a message in English, Sinhala and Tamil.
 *
 * <p>A tile: an id, unique; a source among {@link TileSources}; a kind COUNT, MONEY or PERCENT;
 * a trend only from a source that has one; a drill report that exists (or the exception queue);
 * the label a message in the three languages.
 *
 * <p>28A's fixture render of the A4 template in three languages is not here: every definition
 * prints through the one template m8-report.html, which ReportRunPostgresIntegrationTest renders.
 */
final class DefinitionValidator {

    static final Pattern ID = Pattern.compile("^[a-z0-9-]{1,40}$");

    static final List<Locale> LANGUAGES =
            List.of(Locale.ENGLISH, Locale.forLanguageTag("si"), Locale.forLanguageTag("ta"));

    private DefinitionValidator() {}

    static List<String> reports(List<Definition> definitions, Messages messages) {
        List<String> faults = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Definition d : definitions) {
            String at = "report " + d.reportId() + ": ";
            if (!ID.matcher(d.reportId()).matches()) {
                faults.add(at + "the id must be lower case letters, digits and dashes, at most 40");
            }
            if (!ids.add(d.reportId())) {
                faults.add(at + "the id is used twice");
            }
            Set<String> keys = ReportSources.KEYS.get(d.source());
            if (keys == null) {
                faults.add(at + "no named query " + d.source() + " in ReportSources");
                keys = Set.of();
            }
            for (String parameter : d.parameters()) {
                if (!ReportCatalogue.PARAMETERS.contains(parameter)) {
                    faults.add(at + "unknown parameter " + parameter);
                }
            }
            if (ReportSources.PERIOD.contains(d.source()) && !d.period()) {
                faults.add(at + "the source " + d.source() + " reads a period, so the report must take one");
            }
            if (d.columns().isEmpty()) {
                faults.add(at + "no columns");
            }
            Set<String> seen = new HashSet<>();
            for (Column column : d.columns()) {
                if (!keys.isEmpty() && !keys.contains(column.key())) {
                    faults.add(at + "the source " + d.source() + " does not fill the column " + column.key());
                }
                if (!seen.add(column.key())) {
                    faults.add(at + "the column " + column.key() + " is there twice");
                }
                if (!ReportCatalogue.KINDS.contains(column.kind())) {
                    faults.add(at + "the column " + column.key() + " has an unknown kind " + column.kind());
                }
                message(column.labelId(), at, messages, faults);
            }
            for (String cost : d.costColumns()) {
                if (!seen.contains(cost)) {
                    faults.add(at + "the cost column " + cost + " is not a column");
                }
            }
            message(d.titleId(), at, messages, faults);
            message(d.decisionId(), at, messages, faults);
        }
        return faults;
    }

    static List<String> tiles(List<TileDefinition> tiles, ReportCatalogue reports, Messages messages) {
        List<String> faults = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (TileDefinition tile : tiles) {
            String at = "tile " + tile.tileId() + ": ";
            if (!ID.matcher(tile.tileId()).matches()) {
                faults.add(at + "the id must be lower case letters, digits and dashes, at most 40");
            }
            if (!ids.add(tile.tileId())) {
                faults.add(at + "the id is used twice");
            }
            if (!TileSources.NAMES.contains(tile.source())) {
                faults.add(at + "no named query " + tile.source() + " in TileSources");
            }
            if (!TileCatalogue.KINDS.contains(tile.kind())) {
                faults.add(at + "the kind must be COUNT, MONEY or PERCENT");
            }
            if (tile.trend() && !TileSources.TREND.contains(tile.source())) {
                faults.add(at + "the source " + tile.source() + " has no trend");
            }
            if (tile.drill() != null
                    && !tile.drill().equals(TileCatalogue.EXCEPTIONS)
                    && reports.find(tile.drill()).isEmpty()) {
                faults.add(at + "no report " + tile.drill() + " to open");
            }
            message(tile.labelId(), at, messages, faults);
        }
        return faults;
    }

    private static void message(String id, String at, Messages messages, List<String> faults) {
        for (Locale language : LANGUAGES) {
            if (id == null || messages.text(id, language).fallback()) {
                faults.add(at + "the message " + id + " is missing in " + language.getLanguage());
            }
        }
    }
}
