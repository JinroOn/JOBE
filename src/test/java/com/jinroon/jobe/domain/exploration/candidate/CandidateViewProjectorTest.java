package com.jinroon.jobe.domain.exploration.candidate;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.jinroon.jobe.domain.exploration.api.ExplorationApiException;
import com.jinroon.jobe.domain.exploration.display.*;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CandidateViewProjectorTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ExplorationJson json=new ExplorationJson(mapper);
    final ResultDisplayCatalog wording=new ResultDisplayCatalog(mapper);
    final CandidateViewProjector projector=new CandidateViewProjector(json,new ExplorationResultDisplay(json,wording));
    CandidateViewProjectorTest()throws Exception{}

    @Test void sameBasisIsExplicitButCoincidentTotalAndDifferentProfileAreNotEquivalent() {
        ObjectNode result=result(),bundle=bundle();
        ((ObjectNode)bundle.path("mapping").path("majors").get(2)).put("interest_structure","PRIMARY");
        ((ArrayNode)bundle.path("mapping").path("majors").get(2).path("secondary_interest_axes")).set(0,json.tree("INT_SOC"));
        ((ObjectNode)result.path("rankedCandidates").get(2).path("evidence").path("interest")).putObject("INT_SOC").putNull("score").put("validCount",0).put("inconsistent",false);
        JsonNode view=projector.freeze(result,bundle), response=projector.response(result,view,1,"COMPLETION_SNAPSHOT");
        assertThat(response.path("basisGroups").size()).isEqualTo(1);
        assertThat(response.path("basisGroups").get(0).path("majorCodes")).isEqualTo(json.tree(List.of("A","B")));
        assertThat(response.path("items").get(2).path("basisGroupId").isNull()).isTrue();
        assertThat(response.path("items").get(2).path("isFirstScreen").asBoolean()).isFalse();
        assertThat(response.path("items").findValuesAsText("majorCode")).containsExactly("A","B","C");
        assertThat(response.toString()).doesNotContain("basisInputs","total\"", "topic\"", "score\"", "sourceHashes","source_file", "majorId", "collapsed");
    }
    @Test void activityOrderAndLearningLinksArePartOfTheProfileAndNeverSortedAway() {
        ObjectNode result=result(),bundle=bundle();
        ((ObjectNode)bundle.path("mapping").path("majors").get(1)).set("activity_axes",json.tree(List.of("ACT_CRE","ACT_RES","ACT_EDU")));
        ((ObjectNode)result.path("rankedCandidates").get(1)).set("usedActivityAxes",json.tree(List.of("ACT_CRE","ACT_RES","ACT_EDU")));
        ((ObjectNode)bundle.path("mapping").path("majors").get(2)).set("learning_content_sets",json.tree(List.of("LEARN_SOC")));
        ((ObjectNode)result.path("rankedCandidates").get(2)).set("usedLearningSets",json.tree(List.of("LEARN_SOC")));
        ((ObjectNode)result.path("rankedCandidates").get(2).path("evidence")).putObject("learning").set("LEARN_SOC",result.path("rankedCandidates").get(0).path("evidence").path("learning").path("LEARN_HUM").deepCopy());
        assertThat(projector.freeze(result,bundle).path("basisGroups")).isEmpty();
    }
    @Test void sameProfileWithDifferentObservedCountsOrNullAndZeroIsNotGrouped() {
        ObjectNode result=result();
        ((ObjectNode)result.path("rankedCandidates").get(1).path("evidence").path("interest").path("INT_HUM")).put("validCount",1);
        ((ObjectNode)result.path("rankedCandidates").get(2).path("evidence").path("activity").path("ACT_EDU")).putNull("score").put("validCount",0);
        ((ObjectNode)result.path("rankedCandidates").get(2)).set("usedActivityAxes",json.tree(List.of("ACT_RES","ACT_CRE")));
        assertThat(projector.freeze(result,bundle()).path("basisGroups")).isEmpty();
    }
    @Test void projectionIsDeterministicAndPreservesStoredOrderInsteadOfResortingTies() {
        ObjectNode result=result();
        ArrayNode candidates=(ArrayNode)result.path("rankedCandidates");JsonNode c=candidates.remove(2);candidates.insert(0,c);
        String original=json.write(result);JsonNode view=projector.freeze(result,bundle());
        JsonNode first=projector.response(result,view,1,"COMPLETION_SNAPSHOT");
        assertThat(first.path("items").findValuesAsText("majorCode")).containsExactly("C","A","B");
        assertThat(first).isEqualTo(projector.response(result,view,1,"COMPLETION_SNAPSHOT"));
        assertThat(json.write(result)).isEqualTo(original);
        assertThat(first.path("additionalCount").asInt()).isEqualTo(1);
    }
    @Test void olderWordingIsNotReplacedWithCurrentCatalogLabelsOrDescriptions() {
        ObjectNode b=bundle();b.remove("displayContent");ObjectNode result=result();
        JsonNode out=projector.response(result,projector.freeze(result,b),1,"SEALED_RESULT_PROJECTION");
        assertThat(out.path("groups").get(0).path("name").isNull()).isTrue();
        for(JsonNode card:out.path("items")) {
            assertThat(card.path("name").asText()).startsWith("historical-");
            assertThat(card.path("reasons")).isEmpty();assertThat(card.path("explanation").isNull()).isTrue();
            assertThat(card.path("reasonAvailability").asText()).isEqualTo("UNAVAILABLE");
        }
    }
    @Test void basisMembersCountsAndPrivateFieldInjectionFailClosed() {
        ObjectNode r=result();JsonNode view=projector.freeze(r,bundle());
        ObjectNode injected=view.deepCopy();((ObjectNode)injected.path("cards").path("A")).put("privateAnswer","injected");
        integrity(()->projector.response(r,injected,1,"COMPLETION_SNAPSHOT"));
        ObjectNode invalid=view.deepCopy();((ObjectNode)invalid).put("catalogMajorCount",1);
        integrity(()->projector.response(r,invalid,1,"COMPLETION_SNAPSHOT"));
        ObjectNode group=view.deepCopy();((ArrayNode)group.path("basisGroups").get(0).path("majorCodes")).add("OUTSIDE");
        integrity(()->projector.response(r,group,1,"COMPLETION_SNAPSHOT"));
        ObjectNode wrongVersion=view.deepCopy();wrongVersion.put("viewVersion","FUTURE");
        integrity(()->projector.response(r,wrongVersion,1,"COMPLETION_SNAPSHOT"));
    }
    @Test void duplicatesForeignFirstScreenAndMissingEvidenceDoNotBecomeAnEmptySuccess() {
        ObjectNode duplicate=result();((ArrayNode)duplicate.path("rankedCandidates")).add(duplicate.path("rankedCandidates").get(0));
        integrity(()->projector.freeze(duplicate,bundle()));
        ObjectNode foreign=result();((ObjectNode)foreign.path("firstScreen").get(0)).put("majorCode","OUTSIDE");
        integrity(()->projector.freeze(foreign,bundle()));
        ObjectNode missing=result();((ObjectNode)missing.path("rankedCandidates").get(0)).remove("evidence");
        integrity(()->projector.freeze(missing,bundle()));
    }
    @Test void absentCandidateArraysOmitLegacyMetadataButPresentCorruptViewCannotFallback() {
        ObjectNode old=json.object();old.put("status","READY");assertThat(projector.metadata(old,1)).isNull();
        old.putObject("candidateView");integrity(()->projector.metadata(old,1));
    }
    @Test void noCandidateResultReturnsARealEmptyListWithoutLookingUpProfiles() {
        ObjectNode r=result();r.putArray("rankedCandidates");r.putArray("firstScreen");
        r.put("status","NO_SUPPORTED_CANDIDATE");
        var fixed=(ObjectNode)r.path("fixedCandidates");fixed.putArray("groupCodes");fixed.putArray("majorCodes");
        JsonNode out=projector.response(r,projector.freeze(r,json.object()),1,"SEALED_RESULT_PROJECTION");
        assertThat(out.path("totalCount").asInt()).isZero();assertThat(out.path("items")).isEmpty();assertThat(out.path("groups")).isEmpty();
    }
    private void integrity(Runnable f){assertThatThrownBy(f::run).isInstanceOfSatisfying(ExplorationApiException.class,e->assertThat(e.getCode()).isEqualTo("CATALOG_INTEGRITY_ERROR"));}
    private ObjectNode result(){
        ObjectNode out=json.object();out.put("resultContractVersion","RESULT_CONTRACT_V1_0");out.put("status","READY");out.put("runMode","DEV");out.put("testOnly",true);
        out.putObject("versions").put("majorMapping","synthetic");out.putObject("sourceHashes").put("majorMapping","synthetic");
        var fixed=out.putObject("fixedCandidates");fixed.set("groupCodes",json.tree(List.of("HUM")));fixed.set("majorCodes",json.tree(List.of("A","B","C")));
        var rows=out.putArray("rankedCandidates");
        for(String code:List.of("A","B","C")){
            ObjectNode c=rows.addObject();c.put("majorCode",code);c.put("group","HUM");c.put("topic",75);c.put("activity",75);c.put("learning",75);c.put("total",75);c.put("positiveTopicEvidence",true);
            c.set("usedInterestAxes",json.tree(List.of("INT_HUM")));c.set("usedActivityAxes",json.tree(List.of("ACT_RES","ACT_CRE","ACT_EDU")));c.set("usedLearningSets",json.tree(List.of("LEARN_HUM")));
            var evidence=c.putObject("evidence");axis(evidence.putObject("interest"),"INT_HUM");var activity=evidence.putObject("activity");for(String metric:List.of("ACT_RES","ACT_CRE","ACT_EDU"))axis(activity,metric);axis(evidence.putObject("learning"),"LEARN_HUM");
        }
        out.putArray("firstScreen").add(rows.get(0).deepCopy()).add(rows.get(1).deepCopy());return out;
    }
    private void axis(ObjectNode axes,String code){axes.putObject(code).put("score",75).put("validCount",2).put("inconsistent",false);}
    private ObjectNode bundle(){ObjectNode b=json.object();b.set("displayContent",wording.snapshot());var majors=b.putObject("mapping").putArray("majors");for(String code:List.of("A","B","C")){
        var m=majors.addObject();m.put("major_code",code);m.put("major_name","historical-"+code);m.put("exploration_group","HUM");m.put("interest_structure","SINGLE");
        m.set("primary_interest_axes",json.tree(List.of("INT_HUM")));m.set("secondary_interest_axes",json.tree(List.of("INT_HUM")));m.set("activity_axes",json.tree(List.of("ACT_RES","ACT_CRE","ACT_EDU")));m.set("learning_content_sets",json.tree(List.of("LEARN_HUM")));
    }return b;}
}
