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
    @Test void newDraftHasExplicitStepGroundingAndSixGeneralMethodsWithoutApprovals(){
        var next=loader.validate(Path.of("datasets/activities/catalog-v2.json"));
        assertThat(next.path("activities").size()).isEqualTo(288);
        var methods=new java.util.HashSet<String>();int general=0;
        for(var a:next.path("activities")){
            assertThat(a.path("content_status").asText()).isEqualTo("DRAFT");
            assertThat(a.path("implementation_review_status").asText()).isEqualTo("PENDING");
            assertThat(a.path("domain_review_status").asText()).isEqualTo("PENDING");
            assertThat(a.path("preference_mapping_basis").size()).isEqualTo(a.path("preference_question_ids").size());
            if(a.path("major_code").isNull()){general++;a.path("preference_question_ids").forEach(q->methods.add(q.asText()));}
        }
        assertThat(general).isEqualTo(6);assertThat(methods).isEqualTo(ActivityCatalogLoader.PREFERENCES);
    }
    @Test void newSchemaRejectsMissingOrUnrelatedPreferenceGrounding(){
        var next=(ObjectNode)loader.validate(Path.of("datasets/activities/catalog-v2.json"));
        var item=(ObjectNode)next.path("activities").get(0);var basis=item.remove("preference_mapping_basis");
        assertThatThrownBy(()->loader.validate(next)).isInstanceOf(IllegalArgumentException.class);
        item.set("preference_mapping_basis",basis);
        ((ObjectNode)basis.get(0)).putArray("step_indexes").add(item.path("steps").size());
        assertThatThrownBy(()->loader.validate(next)).isInstanceOf(IllegalArgumentException.class);
        ((ObjectNode)basis.get(0)).putArray("step_indexes").add(0);
        ((ObjectNode)basis.get(0)).put("question_id","ACTIVITY-CONTENT-01");
        assertThatThrownBy(()->loader.validate(next)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void activeInputCannotBypassActivation(){dataset.put("content_status","ACTIVE");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
    @Test void invalidPreferenceTagsAreNotInferredOrAccepted(){((ObjectNode)dataset.path("activities").get(0)).withArray("preference_question_ids").add("DECISION-01");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
    @Test void sourceMustBeAnAttributedWebReference(){((ObjectNode)dataset.path("activities").get(0).path("sources").get(0)).put("url","javascript:alert(1)");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
    @Test void missingExamplesAndRecommendationEffectsAreRejected(){var item=(ObjectNode)dataset.path("activities").get(0);item.put("example_output","");assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);item.put("example_output","test");item.put("changes_recommendation",true);assertThatThrownBy(()->loader.validate(dataset)).isInstanceOf(IllegalArgumentException.class);}
}
