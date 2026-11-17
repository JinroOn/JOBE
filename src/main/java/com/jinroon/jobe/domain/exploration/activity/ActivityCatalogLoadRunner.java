package com.jinroon.jobe.domain.exploration.activity;

import java.nio.file.Path;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Opt-in local management import, after the core major catalog is available. */
@Component
@RequiredArgsConstructor
@Order(100)
@ConditionalOnProperty(prefix="exploration.activities",name="load-on-startup",havingValue="true")
public class ActivityCatalogLoadRunner implements ApplicationRunner {
    private final ActivityCatalogLoader loader;
    @Value("${exploration.activities.dataset-path:datasets/activities/catalog-v2.json}") private String path;
    @Value("${exploration.activities.dry-run:true}") private boolean dryRun;
    public void run(ApplicationArguments args){if(dryRun)loader.validate(Path.of(path));else loader.load(Path.of(path));}
}
