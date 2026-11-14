package com.jinroon.jobe.domain.exploration.catalog;

import java.util.List;
import lombok.Getter;

/**
 * 카탈로그 입력 검증 실패. 하나라도 실패하면 DB에 아무것도 쓰지 않는다(fail closed, D05).
 */
@Getter
public class ExplorationCatalogValidationException extends RuntimeException {

    private final List<String> errors;

    public ExplorationCatalogValidationException(List<String> errors) {
        super("Exploration catalog validation failed (" + errors.size() + "): "
                + String.join("; ", errors.subList(0, Math.min(errors.size(), 10)))
                + (errors.size() > 10 ? "; ..." : ""));
        this.errors = List.copyOf(errors);
    }
}
