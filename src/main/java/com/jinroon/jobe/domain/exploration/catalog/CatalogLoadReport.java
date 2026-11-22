package com.jinroon.jobe.domain.exploration.catalog;

import java.util.List;

/**
 * 적재 결과 요약.
 *
 * @param dryRun   true면 검증만 하고 DB에 쓰지 않았다
 * @param catalogs 카탈로그별 결과
 */
public record CatalogLoadReport(boolean dryRun, List<CatalogEntry> catalogs) {

    public enum Outcome {
        /** 검증만 통과(dry-run) */
        VALIDATED,
        /** 새로 적재 */
        LOADED,
        /** 같은 버전·같은 내용이 이미 있어 건너뜀 */
        ALREADY_LOADED
    }

    public record CatalogEntry(String catalogType, String version, String contentHash, Outcome outcome, int rowCount) {
    }
}
