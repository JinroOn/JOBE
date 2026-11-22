package com.jinroon.jobe.domain.exploration;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.jinroon.jobe.domain.exploration.ai.*;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ExplorationJobLifecycleIntegrationTest extends CompletedExplorationTestSupport {
    @Autowired ExplorationJobOwnerService owners;
    @Autowired ExplorationAiErasureService erasures;
    @Autowired ExplorationJobService jobs;
    @Autowired ResultContextService contexts;
    @Autowired PlatformTransactionManager transactions;
    @MockitoBean ExplorationJobClient remote;
    Set<String> keys=new HashSet<>();
    String key(long user){String key=owners.ownedKey(user);keys.add(key);return key;}
    @Override @AfterEach void cleanup(){super.cleanup();for(String key:keys)jdbc.update("DELETE FROM exploration_ai_erasure_outbox WHERE owner_key=?",key);}
    @Test void mappingIsStableAndCascadeLeavesDurableErasureRequest(){String key=key(OWNER);assertThat(owners.ownedKey(OWNER)).isEqualTo(key);deletion.delete(OWNER);assertThat(count("exploration_ai_owner","user_id=?",OWNER)).isZero();assertThat(count("exploration_ai_erasure_outbox","owner_key=?",key)).isEqualTo(1);assertThatThrownBy(()->owners.ownedKey(OWNER)).isInstanceOf(ExplorationJobClient.Unavailable.class);verifyNoInteractions(remote);}
    @Test void rollbackPreservesAccountAndDoesNotEmitErasure(){String key=key(OWNER);assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{deletion.delete(OWNER);throw new IllegalStateException("rollback");})).isInstanceOf(IllegalStateException.class);assertThat(users.existsById(OWNER)).isTrue();assertThat(count("exploration_ai_erasure_outbox","owner_key=?",key)).isZero();assertThat(owners.ownedKey(OWNER)).isEqualTo(key);}
    @Test void failedRemoteErasureRemainsPendingAndAckIsIdempotent(){String key=key(OWNER);deletion.delete(OWNER);doThrow(new ExplorationJobClient.Unavailable()).when(remote).purge(anyString());assertThat(erasures.drain()).isZero();assertThat(count("exploration_ai_erasure_outbox","owner_key=?",key)).isEqualTo(1);assertThat(jdbc.queryForObject("SELECT attempt_count FROM exploration_ai_erasure_outbox WHERE owner_key=?",Integer.class,key)).isEqualTo(1);doNothing().when(remote).purge(key);assertThat(erasures.drain()).isZero();jdbc.update("UPDATE exploration_ai_erasure_outbox SET next_attempt_at=NOW(6) WHERE owner_key=?",key);assertThat(erasures.drain()).isEqualTo(1);assertThat(erasures.drain()).isZero();}
    @Test void stableMutationReusesRequestAndForeignContextCannotReachRemote()throws Exception{
        long result=completedResult(true);long context=contexts.prepare(OWNER,result,"context","CONSULTATION",0,null).body().path("contextId").asLong();key(OWNER);
        when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","QUEUED"));
        jobs.command(OWNER,context,"CONSULTATION","stable","enqueue");jobs.command(OWNER,context,"CONSULTATION","stable","enqueue");
        var capture=org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(remote,times(2)).command(eq("enqueue"),anyString(),eq("stable"),capture.capture());assertThat(capture.getAllValues().get(0)).isEqualTo(capture.getAllValues().get(1));
        clearInvocations(remote);assertThatThrownBy(()->jobs.command(OTHER,context,"CONSULTATION","stable","read")).isInstanceOf(RuntimeException.class);verifyNoInteractions(remote);
    }
    @Test void accountDeletedDuringNetworkCallDiscardsResponse()throws Exception{
        long result=completedResult(true);long context=contexts.prepare(OWNER,result,"context","CONSULTATION",0,null).body().path("contextId").asLong();String key=key(OWNER);
        when(remote.command(anyString(),anyString(),anyString(),any())).thenAnswer(call->{deletion.delete(OWNER);return mapper.createObjectNode().put("status","QUEUED");});
        assertThatThrownBy(()->jobs.command(OWNER,context,"CONSULTATION","stable","enqueue")).isInstanceOf(RuntimeException.class);
        assertThat(count("exploration_ai_erasure_outbox","owner_key=?",key)).isEqualTo(1);
    }
    @Test void authenticatedHttpEndpointsUseOwnedContextAndRejectForeignAccess()throws Exception{
        long result=completedResult(true);long context=contexts.prepare(OWNER,result,"context","CONSULTATION",0,null).body().path("contextId").asLong();key(OWNER);
        String path="/api/exploration/v1/contexts/"+context+"/jobs/CONSULTATION/stable";
        when(remote.command(anyString(),anyString(),anyString(),any())).thenReturn(mapper.createObjectNode().put("status","QUEUED").put("generationAuthorized",false));
        response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",owner),200);
        response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization",owner),200);
        response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(path).header("Authorization",owner),200);
        clearInvocations(remote);
        response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization",other),404);verifyNoInteractions(remote);
        response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path),401);
        response(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path+"?ownerKey=forged").header("Authorization",owner),400);
    }
}
