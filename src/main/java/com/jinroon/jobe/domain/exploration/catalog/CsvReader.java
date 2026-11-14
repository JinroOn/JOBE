package com.jinroon.jobe.domain.exploration.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카탈로그 CSV(RFC 4180) 읽기. 따옴표로 감싼 값 안의 쉼표·줄바꿈·이중 따옴표("")를 처리하고 UTF-8 BOM을 제거한다.
 * 첫 줄은 헤더이며, 각 행은 헤더 순서를 유지한 Map으로 돌려준다.
 */
final class CsvReader {

    private CsvReader() {
    }

    static List<Map<String, String>> read(String content) {
        List<List<String>> records = parse(stripBom(content));
        if (records.isEmpty()) {
            return List.of();
        }
        List<String> header = records.get(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> record = records.get(i);
            if (record.size() == 1 && record.get(0).isEmpty()) {
                continue; // 빈 줄
            }
            if (record.size() != header.size()) {
                throw new IllegalArgumentException(
                        "CSV row " + (i + 1) + " has " + record.size() + " fields, expected " + header.size());
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                row.put(header.get(c), record.get(c));
            }
            rows.add(row);
        }
        return rows;
    }

    private static String stripBom(String content) {
        return content.startsWith("﻿") ? content.substring(1) : content;
    }

    private static List<List<String>> parse(String content) {
        List<List<String>> records = new ArrayList<>();
        List<String> current = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        int i = 0;
        while (i < content.length()) {
            char ch = content.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                        continue;
                    }
                    quoted = false;
                } else {
                    field.append(ch);
                }
                i++;
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                current.add(field.toString());
                field.setLength(0);
            } else if (ch == '\r' || ch == '\n') {
                current.add(field.toString());
                field.setLength(0);
                records.add(current);
                current = new ArrayList<>();
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                field.append(ch);
            }
            i++;
        }
        if (quoted) {
            throw new IllegalArgumentException("CSV has an unterminated quoted field");
        }
        if (field.length() > 0 || !current.isEmpty()) {
            current.add(field.toString());
            records.add(current);
        }
        return records;
    }
}
