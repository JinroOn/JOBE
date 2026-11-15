package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.activity.ActivityCatalogLoader;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.Path;
import org.junit.jupiter.api.*;

class ActivityCatalogValidationTest {
    private final ObjectMapper mapper=new ObjectMapper();
    private final ActivityCatalogLoader loader=new ActivityCatalogLoader(null,new ExplorationJson(mapper));
    private ObjectNode dataset;
    @BeforeEach void read(){dataset=(ObjectNode)loader.validate(Path.of("datasets/activities/catalog.json"));}
    @Test void preparedDataRetainsDraftsExamplesAndFullSourceAttribution(){
        assertThat(dataset.path("activities").size()).isEqualTo(282);
        for(var item:dataset.path("activities")) {
            assertThat(item.path("content_status").asText()).isEqualTo("DRAFT");
            assertThat(item.path("domain_review_status").asText()).isEqualTo("PENDING");
            assertThat(item.path("preference_question_ids").isEmpty()).isTrue();
            assertThat(item.path("worksheet_markdown").asText()).isNotBlank();
            assertThat(item.path("example_output").asText()).isNotBlank();
        }
    }
    @Test void duplicateActivityIdentityIsRejected(){dataset.withArray("activities").add(dataset.path("activities").get(0).deepCopy());assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
    @Test void activeInputCannotBypassActivation(){dataset.put("content_status","ACTIVE");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
    @Test void invalidPreferenceTagsAreNotInferredOrAccepted(){((ObjectNode)dataset.path("activities").get(0)).withArray("preference_question_ids").add("DECISION-01");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
    @Test void sourceMustBeAnAttributedWebReference(){((ObjectNode)dataset.path("activities").get(0).path("sources").get(0)).put("url","javascript:alert(1)");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
    @Test void missingExamplesAndRecommendationEffectsAreRejected(){var item=(ObjectNode)dataset.path("activities").get(0);item.put("example_output","");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);item.put("example_output","test");item.put("changes_recommendation",true);assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
}
