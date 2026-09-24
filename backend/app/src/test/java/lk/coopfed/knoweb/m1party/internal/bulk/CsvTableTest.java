package lk.coopfed.knoweb.m1party.internal.bulk;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CsvTableTest {

    @Test
    void readsAHeaderAndRowsWithTheirLineNumbers() {
        CsvTable table = CsvTable.parse("entity_code,entity_type,legal_name_en\nM001,MPCS,One\nM002,MPCS,Two\n");

        assertThat(table.header()).containsExactly("entity_code", "entity_type", "legal_name_en");
        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).line()).isEqualTo(2);
        assertThat(table.rows().get(1).line()).isEqualTo(3);
        assertThat(table.rows().get(1).get("legal_name_en")).isEqualTo("Two");
    }

    @Test
    void headerNamesAreCaseAndSpaceInsensitive() {
        CsvTable table = CsvTable.parse(" Entity_Code , ENTITY_TYPE\nM001,MPCS\n");

        assertThat(table.header()).containsExactly("entity_code", "entity_type");
    }

    @Test
    void quotedFieldsMayHoldCommasQuotesAndLineBreaks() {
        CsvTable table = CsvTable.parse(
                "entity_code,legal_name_en\nM001,\"Society, \"\"the\"\" one\nover two lines\"\nM002,Plain\n");

        assertThat(table.rows()).hasSize(2);
        assertThat(table.rows().get(0).get("legal_name_en")).isEqualTo("Society, \"the\" one\nover two lines");
        assertThat(table.rows().get(1).line())
                .as("the quoted line break counts as a line")
                .isEqualTo(4);
    }

    @Test
    void windowsLineEndsAByteOrderMarkAndBlankLinesAreTolerated() {
        CsvTable table = CsvTable.parse("﻿entity_code,legal_name_en\r\nM001,One\r\n\r\nM002,Two\r\n");

        assertThat(table.rows()).extracting(row -> row.get("entity_code")).containsExactly("M001", "M002");
        assertThat(table.rows().get(1).line()).isEqualTo(4);
    }

    @Test
    void aShortRowReadsMissingColumnsAsEmpty() {
        CsvTable table = CsvTable.parse("entity_code,entity_type,legal_name_en\nM001\n");

        assertThat(table.rows().get(0).get("legal_name_en")).isEmpty();
    }

    @Test
    void anEmptyFileHasNoHeader() {
        assertThat(CsvTable.parse("").header()).isEmpty();
        assertThat(CsvTable.parse("\n\n").header()).isEmpty();
    }
}
