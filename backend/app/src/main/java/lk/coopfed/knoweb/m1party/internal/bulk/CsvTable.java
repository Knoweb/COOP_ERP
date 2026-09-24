package lk.coopfed.knoweb.m1party.internal.bulk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A CSV file as a header and rows, read the way spreadsheets write them: comma separated,
 * a field may be quoted with double quotes and then contain commas, quotes ({@code ""}) and
 * line breaks; CRLF or LF line ends; an optional byte-order mark. Blank lines are skipped.
 * Header names are lower-cased and trimmed, so {@code Entity_Code} and {@code entity_code}
 * are the same column.
 */
final class CsvTable {

    private final List<String> header;
    private final List<Row> rows;

    private CsvTable(List<String> header, List<Row> rows) {
        this.header = header;
        this.rows = rows;
    }

    /** A data row: the line it starts on (the header is line 1) and its values by column name. */
    record Row(int line, Map<String, String> values) {

        String get(String column) {
            String value = values.get(column);
            return value == null ? "" : value.strip();
        }
    }

    List<String> header() {
        return header;
    }

    List<Row> rows() {
        return rows;
    }

    static CsvTable parse(String text) {
        List<List<String>> records = new ArrayList<>();
        List<Integer> lines = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int line = 1;
        int recordLine = 1;
        String s = text.startsWith("﻿") ? text.substring(1) : text;

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                // CRLF: the LF that follows ends the record.
            } else if (c == '\n') {
                record.add(field.toString());
                field.setLength(0);
                if (!isBlank(record)) {
                    records.add(record);
                    lines.add(recordLine);
                }
                record = new ArrayList<>();
                line++;
                recordLine = line;
            } else {
                field.append(c);
            }
        }
        record.add(field.toString());
        if (!isBlank(record)) {
            records.add(record);
            lines.add(recordLine);
        }

        if (records.isEmpty()) {
            return new CsvTable(List.of(), List.of());
        }

        List<String> header = records.get(0).stream()
                .map(name -> name.strip().toLowerCase(Locale.ROOT))
                .toList();
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            Map<String, String> values = new LinkedHashMap<>();
            List<String> fields = records.get(r);
            for (int c = 0; c < header.size(); c++) {
                values.put(header.get(c), c < fields.size() ? fields.get(c) : "");
            }
            rows.add(new Row(lines.get(r), values));
        }
        return new CsvTable(header, rows);
    }

    private static boolean isBlank(List<String> record) {
        return record.stream().allMatch(String::isBlank);
    }
}
