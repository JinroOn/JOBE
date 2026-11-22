package com.jinroon.jobe.domain.exploration.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.jinroon.jobe.domain.exploration.activity.ActivityRecommendationService;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/**
 * 활동 추천과 활성화 관리가 같은 활동 카탈로그 버전을 기준으로 삼는지 확인한다.
 * 설정(exploration.activities.catalog-version)이 빠진 환경에서 두 기본값이 다르면
 * 추천은 한 버전을 쓰는데 보관 보호는 다른 버전에 걸려, 사용 중인 카탈로그를 보관할 수 있다.
 */
class ActivityCatalogVersionDefaultTest {

    private static final String PROPERTY = "exploration.activities.catalog-version";

    @Test
    void recommendationAndActivationShareTheSameDefaultVersion() throws Exception {
        String recommendation = defaultOf(ActivityRecommendationService.class, "selectedVersion");
        String activation = defaultOf(CatalogActivationService.class, "activityCatalogVersion");

        assertThat(activation).isEqualTo(recommendation);
        assertThat(recommendation).isEqualTo(ActivityRecommendationService.DEFAULT_CATALOG_VERSION);
    }

    private static String defaultOf(Class<?> type, String fieldName) throws NoSuchFieldException {
        Field field = type.getDeclaredField(fieldName);
        String expression = field.getAnnotation(Value.class).value();
        String prefix = "${" + PROPERTY + ":";
        assertThat(expression).as(type.getSimpleName()).startsWith(prefix).endsWith("}");
        return expression.substring(prefix.length(), expression.length() - 1);
    }
}
