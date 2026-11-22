package com.jinroon.jobe.domain.exploration.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    @Test
    void readsQuotedFieldsWithCommasQuotesAndNewlines() {
        String csv = "﻿\"id\",\"text\",\"note\"\r\n"
                + "\"Q1\",\"하나, 둘\",\"그는 \"\"좋다\"\"고 했다\"\r\n"
                + "\"Q2\",\"첫 줄\n둘째 줄\",\"\"\r\n";

        List<Map<String, String>> rows = CsvReader.read(csv);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("id", "Q1").containsEntry("text", "하나, 둘")
                .containsEntry("note", "그는 \"좋다\"고 했다");
        assertThat(rows.get(1)).containsEntry("text", "첫 줄\n둘째 줄").containsEntry("note", "");
    }

    @Test
    void keepsHeaderOrderAndToleratesMissingTrailingNewline() {
        List<Map<String, String>> rows = CsvReader.read("b,a\n2,1");

        assertThat(rows.get(0).keySet()).containsExactly("b", "a");
        assertThat(rows.get(0)).containsEntry("a", "1");
    }

    @Test
    void rejectsRowWithWrongFieldCount() {
        assertThatThrownBy(() -> CsvReader.read("a,b\n1,2,3\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expected 2");
    }

    @Test
    void rejectsUnterminatedQuote() {
        assertThatThrownBy(() -> CsvReader.read("a\n\"open\n"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
