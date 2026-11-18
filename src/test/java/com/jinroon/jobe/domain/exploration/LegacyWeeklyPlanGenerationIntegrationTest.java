package com.jinroon.jobe.domain.exploration;

import com.jinroon.jobe.domain.plan.service.PlanService;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.response.WeeklyPlanResponse;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;
import com.jinroon.jobe.global.exception.CustomException;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;

/** Real disposable MySQL transactions; never real provider requests or development data. */
class LegacyWeeklyPlanGenerationIntegrationTest extends ExplorationApiTestSupport {
    @Autowired PlanService plans;
    @Autowired com.jinroon.jobe.domain.result.service.ResultService results;
    @Autowired javax.sql.DataSource dataSource;
    @MockitoBean AiServiceClient ai;
    @MockitoSpyBean MajorWeeklyPlanItemRepository items;

    private Map<String,Object> input(){
        long old=unrelatedLegacyPlan();
        var row=jdbc.queryForMap("SELECT diagnosis_result_id,result_major_score_id FROM major_weekly_plans WHERE id=?",old);
        long result=((Number)row.get("diagnosis_result_id")).longValue();
        long session=jdbc.queryForObject("SELECT diagnosis_session_id FROM diagnosis_results WHERE id=?",Long.class,result);
        jdbc.update("INSERT INTO competency_eval_results(diagnosis_session_id,math_logic,problem_solving,info_tech,implementation,system_understanding,data_analysis,communication,collaboration,self_management) VALUES (?,50,50,50,50,50,50,50,50,50)",session);
        return Map.of("diagnosisResultId",result,"resultMajorScoreId",row.get("result_major_score_id"));
    }
    private WeeklyPlanResponse response(boolean fallback){
        return new WeeklyPlanResponse("SYNTHETIC_119","SYNTHETIC overview",IntStream.rangeClosed(1,12).mapToObj(n->
            new WeeklyPlanResponse.WeeklyPlan(n,"SYNTHETIC goal",List.of("SYNTHETIC task"),List.of(),"SYNTHETIC note")).toList(),List.of(),"TEST","TEST",fallback,fallback?List.of("llm_timeout_fallback"):List.of());
    }

    // These requests open independent reservation transactions: fixtures must already be committed.
    // ExplorationApiTestSupport deletes only these users and their fixture records after each test.
    @Test void planWithMajorScoreOfAnotherResultIsRejected() throws Exception {
        var input=input();
        var result=jdbc.queryForObject("SELECT diagnosis_result_id FROM major_weekly_plans WHERE result_major_score_id=?",Long.class,input.get("resultMajorScoreId"));
        jdbc.update("UPDATE diagnosis_results SET user_id=? WHERE id=?",OWNER,result);
        long otherPlan=unrelatedLegacyPlan();
        long otherScore=jdbc.queryForObject("SELECT result_major_score_id FROM major_weekly_plans WHERE id=?",Long.class,otherPlan);
        response(post("/api/plans").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("diagnosisResultId",result,"resultMajorScoreId",otherScore))),404);
        verifyNoInteractions(ai);
    }

    @Test void planForOtherUsersResultIsForbidden() throws Exception {
        var input=input();
        response(post("/api/plans").header("Authorization",owner).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(input)),404);
        verifyNoInteractions(ai);
    }

    @Test void providerRunsOutsideTransactionAfterCommittedReservationAndRejectsConcurrentDuplicate()throws Exception{
        var input=input();
        when(ai.getWeeklyPlan(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM major_weekly_plans WHERE diagnosis_result_id=? AND ai_plan_status='PENDING'",Integer.class,input.get("diagnosisResultId"))).isEqualTo(1);
            assertThatThrownBy(()->plans.createPlanForUser(input,OTHER)).isInstanceOf(CustomException.class);
            return response(false);
        });
        var created=response(post("/api/plans").header("Authorization",other).contentType(MediaType.APPLICATION_JSON)
            .content(mapper.writeValueAsString(input)),201);
        var plan=plans.getPlanForUser(created.path("id").asLong(),OTHER);
        assertThat(plan.getAiPlanStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        assertThat(plans.findItemsForUser(plan.getId(),OTHER)).hasSize(12);
        verify(ai,times(1)).getWeeklyPlan(any());
    }

    @Test void timeoutIsRecordedWithoutInventedItemsAndExplicitNewRequestCanUseServerFallback(){
        var input=input();when(ai.getWeeklyPlan(any())).thenThrow(new AiServiceClient.WeeklyPlanFailure("AI_WEEKLY_PLAN_TIMEOUT"));
        var failed=plans.createPlanForUser(input,OTHER);
        assertThat(failed.getAiPlanErrorMessage()).isEqualTo("AI_WEEKLY_PLAN_TIMEOUT");
        assertThat(failed.getFallback()).isFalse();assertThat(failed.getActiveVersion()).isFalse();
        assertThat(plans.findItemsForUser(failed.getId(),OTHER)).isEmpty();
        doReturn(response(true)).when(ai).getWeeklyPlan(any());
        var retried=plans.createPlanForUser(input,OTHER);
        assertThat(retried.getVersionNo()).isEqualTo(failed.getVersionNo()+1);
        assertThat(retried.getFallback()).isTrue();assertThat(retried.getAiPlanStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
        verify(ai,times(2)).getWeeklyPlan(any());
    }

    @Test void withdrawalDuringProviderWaitPreventsAnyLatePublication(){
        var input=input();var pendingId=new java.util.concurrent.atomic.AtomicLong();when(ai.getWeeklyPlan(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            pendingId.set(jdbc.queryForObject("SELECT id FROM major_weekly_plans WHERE diagnosis_result_id=? AND ai_plan_status='PENDING'",Long.class,input.get("diagnosisResultId")));
            deletion.delete(OTHER);return response(false);
        });
        assertThatThrownBy(()->plans.createPlanForUser(input,OTHER)).isInstanceOf(CustomException.class);
        assertThat(count("major_weekly_plans","diagnosis_result_id=?",input.get("diagnosisResultId"))).isZero();
        assertThat(count("major_weekly_plan_items","weekly_plan_id=?",pendingId.get())).isZero();
    }

    @Test void publicationFailureRollsBackAllInsertedItemsBeforeRecordingFailure(){
        var input=input();when(ai.getWeeklyPlan(any())).thenReturn(response(false));
        doAnswer(call->{
            List<MajorWeeklyPlanItem> batch=call.getArgument(0);
            items.saveAllAndFlush(batch);
            throw new IllegalStateException("SYNTHETIC_PERSISTENCE_FAILURE");
        }).when(items).saveAll(any());
        var plan=plans.createPlanForUser(input,OTHER);
        assertThat(plan.getAiPlanErrorMessage()).isEqualTo("AI_WEEKLY_PLAN_PERSISTENCE_FAILED");
        assertThat(plan.getPlanId()).isNull();assertThat(plan.getOverview()).isNull();
        assertThat(plans.findItemsForUser(plan.getId(),OTHER)).isEmpty();
    }

    @Test void stalePendingReservationCanBeRetriedWithoutAnAutomaticProviderRetry(){
        var input=input();
        jdbc.update("UPDATE major_weekly_plans SET ai_plan_status='PENDING',ai_plan_requested_at=DATE_SUB(NOW(),INTERVAL 3 MINUTE) WHERE diagnosis_result_id=?",input.get("diagnosisResultId"));
        when(ai.getWeeklyPlan(any())).thenReturn(response(false));
        var plan=plans.createPlanForUser(input,OTHER);
        assertThat(plan.getVersionNo()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ai_plan_error_message FROM major_weekly_plans WHERE id=?",String.class,plan.getParentPlanId())).isEqualTo("AI_WEEKLY_PLAN_EXPIRED");
        verify(ai,times(1)).getWeeklyPlan(any());
    }

    @Test void expiredGenerationCannotOverwriteItsReplacement(){
        var input=input();var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(ai.getWeeklyPlan(any())).thenAnswer(call->{
            if(calls.getAndIncrement()==0){
                jdbc.update("UPDATE major_weekly_plans SET ai_plan_requested_at=DATE_SUB(NOW(),INTERVAL 3 MINUTE) WHERE diagnosis_result_id=? AND ai_plan_status='PENDING'",input.get("diagnosisResultId"));
                assertThat(plans.createPlanForUser(input,OTHER).getAiPlanStatus()).isEqualTo(AiGenerationStatus.SUCCEEDED);
            }
            return response(false);
        });
        assertThatThrownBy(()->plans.createPlanForUser(input,OTHER)).isInstanceOf(CustomException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM major_weekly_plans WHERE diagnosis_result_id=? AND ai_plan_status='SUCCEEDED'",Integer.class,input.get("diagnosisResultId"))).isEqualTo(1);
    }

    @Test void createIgnoresClientVersionFallbackAndOverviewFields(){
        var input=new HashMap<>(input());input.put("versionNo",999);input.put("parentPlanId",999L);
        input.put("fallback",true);input.put("overview","CLIENT_OVERRIDE");input.put("activeVersion",false);
        when(ai.getWeeklyPlan(any())).thenAnswer(call->{
            var row=jdbc.queryForMap("SELECT version_no,fallback,overview,active_version FROM major_weekly_plans WHERE diagnosis_result_id=? AND ai_plan_status='PENDING'",input.get("diagnosisResultId"));
            assertThat(((Number)row.get("version_no")).intValue()).isEqualTo(2);
            assertThat(row.get("overview")).isNull();
            return response(false);
        });
        var plan=plans.createPlanForUser(input,OTHER);
        assertThat(plan.getVersionNo()).isEqualTo(2);assertThat(plan.getFallback()).isFalse();
        assertThat(plan.getOverview()).isEqualTo("SYNTHETIC overview");
    }

    @Test void concurrentCreatesLeaveOnlyOneCommittedPendingReservation()throws Exception{
        var input=input();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(ai.getWeeklyPlan(any())).thenAnswer(call->{entered.countDown();assertThat(release.await(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return response(false);});
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var completed=new java.util.concurrent.ExecutorCompletionService<Object>(pool);
        var start=new java.util.concurrent.CyclicBarrier(2);
        java.util.concurrent.Callable<Object> create=()->{start.await();try{return plans.createPlanForUser(input,OTHER);}catch(CustomException failure){return failure;}};
        try{
            completed.submit(create);completed.submit(create);
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var loser=completed.poll(5,java.util.concurrent.TimeUnit.SECONDS);assertThat(loser).isNotNull();
            assertThat(loser.get()).isInstanceOf(CustomException.class);
            release.countDown();assertThat(completed.take().get()).isInstanceOf(com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan.class);
            verify(ai,times(1)).getWeeklyPlan(any());
        }finally{release.countDown();pool.shutdownNow();assertThat(pool.awaitTermination(10,java.util.concurrent.TimeUnit.SECONDS)).isTrue();}
    }

    @Test void planDeletedInAnotherTransactionDuringHttpWaitIsNotResurrected()throws Exception{
        var input=input();var pending=new java.util.concurrent.atomic.AtomicLong();
        when(ai.getWeeklyPlan(any())).thenAnswer(call->{
            pending.set(jdbc.queryForObject("SELECT id FROM major_weekly_plans WHERE diagnosis_result_id=? AND ai_plan_status='PENDING'",Long.class,input.get("diagnosisResultId")));
            java.util.concurrent.CompletableFuture.runAsync(()->jdbc.update("DELETE FROM major_weekly_plans WHERE id=?",pending.get())).get(5,java.util.concurrent.TimeUnit.SECONDS);
            return response(false);
        });
        response(post("/api/plans").header("Authorization",other).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(input)),404);
        assertThat(count("major_weekly_plans","id=?",pending.get())).isZero();
        assertThat(count("major_weekly_plan_items","weekly_plan_id=?",pending.get())).isZero();
    }

    @Test void automaticLegacyPlanStartsOnlyAfterResultTransactionAndConnectionAreReleased()throws Exception{
        var input=input();long result=((Number)input.get("diagnosisResultId")).longValue();
        long session=jdbc.queryForObject("SELECT diagnosis_session_id FROM diagnosis_results WHERE id=?",Long.class,result);
        jdbc.update("DELETE FROM major_weekly_plans WHERE diagnosis_result_id=?",result);
        jdbc.update("INSERT INTO tendency_eval_results(diagnosis_session_id,logical_inquiry,practical_tech,art_creative,social_cooperation,life_health,education_guide,theory_academic,data_analytics,system_operation) VALUES (?,50,50,50,50,50,50,50,50,50)",session);
        when(ai.getWeeklyPlan(any())).thenAnswer(call->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class).getHikariPoolMXBean().getActiveConnections()).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM diagnosis_sessions WHERE id=?",String.class,session)).isEqualTo("completed");
            return response(false);
        });
        results.completeDiagnosisResultForUser(session,OTHER);
        verify(ai,times(1)).getWeeklyPlan(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM major_weekly_plans WHERE diagnosis_result_id=? AND ai_plan_status='SUCCEEDED'",Integer.class,result)).isEqualTo(1);
    }
}
