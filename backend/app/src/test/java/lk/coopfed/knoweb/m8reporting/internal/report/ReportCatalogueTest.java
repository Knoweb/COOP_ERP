package lk.coopfed.knoweb.m8reporting.internal.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.m8reporting.internal.report.ReportCatalogue.Definition;
import lk.coopfed.knoweb.m8reporting.internal.report.TileCatalogue.TileDefinition;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The report definitions and the dashboard tiles as data (28A section 7, DefinitionValidator):
 * the seeded files pass, and each kind of fault is refused with its reason.
 */
class ReportCatalogueTest {

    /** M8's own catalogue in three languages, as the kernel would merge it. */
    private static final Messages MESSAGES = messages();

    @Test
    void theSeededDefinitionsAndTilesAreValid() {
        ReportCatalogue reports = new ReportCatalogue(MESSAGES);
        TileCatalogue tiles = new TileCatalogue(reports, MESSAGES);

        assertThat(reports.all()).extracting(Definition::reportId).contains("stock-position", "receivables-ageing");
        assertThat(reports.find("stock-position").orElseThrow().costColumns()).containsExactly("value");
        assertThat(reports.find("fill-rate-delivery").orElseThrow().period()).isTrue();
        assertThat(reports.find("receivables-ageing").orElseThrow().period()).isFalse();
        assertThat(tiles.all()).extracting(TileDefinition::tileId).contains("sales", "exposure", "exceptions");
        assertThat(tiles.all().stream().filter(TileDefinition::trend).map(TileDefinition::tileId))
                .containsExactlyInAnyOrder("sales", "purchases", "shop-sales");
    }

    @Test
    void aDefinitionWithAnUnknownSourceColumnParameterOrMessageIsRefused() {
        Definition good = new ReportCatalogue(MESSAGES).find("invoices-issued").orElseThrow();
        Definition bad = new Definition(
                "Bad Id",
                "m8.no.such.title",
                good.decisionId(),
                "select-anything",
                List.of("period", "customer"),
                List.of(new Column("x", "m8.col.date", "WIDGET")),
                Set.of("y"));

        List<String> faults = DefinitionValidator.reports(List.of(good, good, bad), MESSAGES);

        assertThat(String.join("\n", faults))
                .contains("report invoices-issued: the id is used twice")
                .contains("report Bad Id: the id must be lower case")
                .contains("no named query select-anything")
                .contains("unknown parameter customer")
                .contains("unknown kind WIDGET")
                .contains("the cost column y is not a column")
                .contains("the message m8.no.such.title is missing in si");
        assertThrows(IllegalStateException.class, () -> new ReportCatalogue(List.of(bad), MESSAGES));
    }

    @Test
    void aSourceThatReadsAPeriodNeedsAReportThatTakesOneAndItsKeys() {
        Definition good = new ReportCatalogue(MESSAGES).find("invoices-issued").orElseThrow();
        Definition noPeriod = new Definition(
                "no-period",
                good.titleId(),
                good.decisionId(),
                "invoices-issued",
                List.of(),
                List.of(new Column("skuCode", "m8.col.sku_code", "TEXT")),
                Set.of());

        assertThat(DefinitionValidator.reports(List.of(noPeriod), MESSAGES))
                .anyMatch(f -> f.contains("reads a period"))
                .anyMatch(f -> f.contains("does not fill the column skuCode"));
    }

    @Test
    void aTileWithAnUnknownSourceKindTrendOrDrillIsRefused() {
        ReportCatalogue reports = new ReportCatalogue(MESSAGES);
        TileDefinition bad =
                new TileDefinition("bad", "m8.tile.sales", "TEXT", "receivables", "no-report", true, false);
        TileDefinition unknown = new TileDefinition("x", "m8.tile.sales", "COUNT", "nothing", null, false, false);

        List<String> faults = DefinitionValidator.tiles(List.of(bad, unknown), reports, MESSAGES);

        assertThat(String.join("\n", faults))
                .contains("tile bad: the kind must be COUNT, MONEY or PERCENT")
                .contains("tile bad: the source receivables has no trend")
                .contains("tile bad: no report no-report to open")
                .contains("tile x: no named query nothing");
        assertThrows(IllegalStateException.class, () -> new TileCatalogue(List.of(bad), reports, MESSAGES));
    }

    private static Messages messages() {
        Map<String, Map<String, String>> byLanguage = new HashMap<>();
        ObjectMapper json = new ObjectMapper();
        for (String language : List.of("en", "si", "ta")) {
            try (InputStream in = new ClassPathResource("i18n/m8reporting/" + language + ".json").getInputStream()) {
                byLanguage.put(language, json.readValue(in, new TypeReference<Map<String, String>>() {}));
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return (id, locale, args) -> {
            String text =
                    byLanguage.getOrDefault(locale.getLanguage(), Map.of()).get(id);
            return text == null ? new Messages.Text(id, true) : new Messages.Text(text, false);
        };
    }
}
