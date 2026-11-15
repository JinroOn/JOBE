package com.jinroon.jobe.domain.exploration.catalog;

import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 카탈로그를 적재한다. 기본값은 꺼짐이며, 개발·파일럿 환경에서만
 * EXPLORATION_CATALOG_LOAD_ON_STARTUP=true로 켠다.
 * EXPLORATION_CATALOG_DRY_RUN=true면 검증만 하고 쓰지 않는다.
 * 검증에 실패하면 예외로 시작을 중단해 잘못된 카탈로그로 서비스가 뜨지 않게 한다.
 */
@Slf4j
@Component
@org.springframework.core.annotation.Order(0)
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "exploration.catalog", name = "load-on-startup", havingValue = "true")
public class ExplorationCatalogLoadRunner implements ApplicationRunner {

    private final ExplorationCatalogLoader loader;

    @Value("${exploration.catalog.dataset-path}")
    private String datasetPath;

    @Value("${exploration.catalog.dry-run:false}")
    private boolean dryRun;

    @Override
    public void run(ApplicationArguments args) {
        Path dir = Path.of(datasetPath);
        CatalogLoadReport report = dryRun ? loader.validate(dir) : loader.load(dir);
        log.info("Exploration catalog {} from {}: {}", dryRun ? "validated" : "loaded", dir.toAbsolutePath(),
                report.catalogs());
    }
}
