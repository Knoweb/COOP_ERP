package lk.coopfed.knoweb.m8reporting.internal.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lk.coopfed.knoweb.kernel.api.Messages;
import lk.coopfed.knoweb.m8reporting.query.ReportDefinitionView;
import lk.coopfed.knoweb.m8reporting.query.ReportTable.Column;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

/**
 * The report definitions as data (28A section 3.1, report_definition): read once at start from
 * {@code seed/m8reporting/report-definitions.yaml} and checked by {@link DefinitionValidator},
 * which refuses the start on any fault. Each definition names its source, a named projection
 * query of {@link ReportSources} (the allow-list: a definition never carries SQL), its
 * parameters and its columns.
 */
@Component
class ReportCatalogue {

    static final String RESOURCE = "seed/m8reporting/report-definitions.yaml";

    static final String TEXT = "TEXT";
    static final String DATE = "DATE";
    static final String QTY = "QTY";
    static final String COUNT = "COUNT";
    static final String MONEY = "MONEY";
    static final String PERCENT = "PERCENT";

    static final Set<String> KINDS = Set.of(TEXT, DATE, QTY, COUNT, MONEY, PERCENT);

    /** The parameters a definition may take. */
    static final Set<String> PARAMETERS = Set.of("period", "location");

    /**
     * One definition.
     *
     * @param source      the named query of {@link ReportSources}
     * @param parameters  period (from and to, required) and/or location (optional)
     * @param costColumns the keys of the cost columns
     */
    record Definition(
            String reportId,
            String titleId,
            String decisionId,
            String source,
            List<String> parameters,
            List<Column> columns,
            Set<String> costColumns) {

        boolean period() {
            return parameters.contains("period");
        }

        boolean location() {
            return parameters.contains("location");
        }

        ReportDefinitionView view() {
            return new ReportDefinitionView(reportId, titleId, decisionId, period(), location());
        }
    }

    private final List<Definition> all;

    @org.springframework.beans.factory.annotation.Autowired
    ReportCatalogue(Messages messages) {
        this(read(RESOURCE), messages);
    }

    ReportCatalogue(List<Definition> definitions, Messages messages) {
        List<String> faults = DefinitionValidator.reports(definitions, messages);
        if (!faults.isEmpty()) {
            throw new IllegalStateException("The report definitions are not valid: " + String.join("; ", faults));
        }
        this.all = List.copyOf(definitions);
    }

    List<Definition> all() {
        return all;
    }

    Optional<Definition> find(String reportId) {
        return all.stream().filter(d -> d.reportId().equals(reportId)).findFirst();
    }

    /** Reads the YAML file into definitions, as written: the validator judges them. */
    @SuppressWarnings("unchecked")
    static List<Definition> read(String resource) {
        Map<String, Object> root;
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            root = new Yaml().load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + resource, e);
        }
        List<Definition> definitions = new ArrayList<>();
        for (Map<String, Object> entry : (List<Map<String, Object>>) root.getOrDefault("reports", List.of())) {
            List<String> parameters = (List<String>) entry.getOrDefault("parameters", List.of());
            List<Column> columns = new ArrayList<>();
            Set<String> cost = new LinkedHashSet<>();
            for (Map<String, Object> column : (List<Map<String, Object>>) entry.getOrDefault("columns", List.of())) {
                String key = String.valueOf(column.get("key"));
                columns.add(new Column(key, String.valueOf(column.get("label")), String.valueOf(column.get("kind"))));
                if (Boolean.TRUE.equals(column.get("cost"))) {
                    cost.add(key);
                }
            }
            definitions.add(new Definition(
                    String.valueOf(entry.get("id")),
                    String.valueOf(entry.get("title")),
                    String.valueOf(entry.get("decision")),
                    String.valueOf(entry.get("source")),
                    List.copyOf(parameters),
                    List.copyOf(columns),
                    Set.copyOf(cost)));
        }
        return definitions;
    }
}
