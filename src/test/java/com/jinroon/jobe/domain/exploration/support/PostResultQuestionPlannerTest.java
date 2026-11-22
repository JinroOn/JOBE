package com.jinroon.jobe.domain.exploration.support;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.exploration.catalog.BranchCatalog;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;

class PostResultQuestionPlannerTest {
    private static BranchCatalog catalog;
    @BeforeAll static void load()throws Exception {
        var mapper=new ObjectMapper();Path root=Path.of("datasets/exploration");
        catalog=new BranchCatalog(mapper.readTree(Files.readString(root.resolve("ruleset.json"))),mapper.readTree(Files.readString(root.resolve("major-mapping.json"))),mapper.readTree(Files.readString(root.resolve("semantic-dictionary.json"))),
                Files.readString(root.resolve("questions.csv")),Files.readString(root.resolve("options.csv")),Files.readString(root.resolve("option-variants.csv")),Files.readString(root.resolve("comparison-skip-pairs.csv")));
    }
    @Test void purposesUseOnlyFixedPostResultCatalogAndDoNotFillNonexistentSlots() {
        var planner=new PostResultQuestionPlanner();assertThat(planner.desired(catalog,"PLAN")).hasSize(8);assertThat(planner.desired(catalog,"ACTIVITY")).hasSize(6);assertThat(planner.desired(catalog,"BOTH")).hasSize(8);
        for(String purpose:List.of("PLAN","ACTIVITY","BOTH"))assertThat(planner.desired(catalog,purpose)).doesNotHaveDuplicates().allMatch(k->catalog.question(k.questionId()).role().name().equals("POST_RESULT_ONLY"));
    }
    @Test void bothUsesCanonicalFourPlusFourAndNeverIncludesPhaseTwoOnlyModules() {
        var keys=new PostResultQuestionPlanner().desired(catalog,"BOTH");
        assertThat(keys.subList(0,4).stream().map(BranchCatalog.Key::questionId).toList()).containsExactly("READINESS-INFO-01","READINESS-CMP-01","READINESS-SELF-01","READINESS-ACT-02");
        assertThat(keys.subList(4,8).stream().map(BranchCatalog.Key::questionId).toList()).containsExactly("ACTIVITY-REPORT-01","ACTIVITY-PROJECT-01","ACTIVITY-READ-01","ACTIVITY-EXPERIMENT-01");
        assertThat(keys).allMatch(k->Set.of("READINESS","EXPLORATION_ACTIVITY").contains(catalog.question(k.questionId()).module()));
    }
    @Test void unknownPurposeIsNotSilentlyConvertedToPlan() {
        assertThatThrownBy(()->new PostResultQuestionPlanner().desired(catalog,"UNKNOWN")).isInstanceOf(IllegalArgumentException.class);
    }
}
