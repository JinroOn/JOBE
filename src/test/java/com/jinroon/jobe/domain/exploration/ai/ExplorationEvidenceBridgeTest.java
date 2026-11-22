package com.jinroon.jobe.domain.exploration.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jinroon.jobe.domain.exploration.context.ResultContextService;
import com.jinroon.jobe.domain.exploration.service.ExplorationJson;
import java.nio.file.*;
import org.junit.jupiter.api.*;

class ExplorationEvidenceBridgeTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ExplorationAiContract contract=new ExplorationAiContract(new ExplorationJson(mapper));
    final ResultContextService contexts=mock(ResultContextService.class);
    final ExplorationEvidenceClient client=mock(ExplorationEvidenceClient.class);
    final ExplorationAiInputService service=new ExplorationAiInputService(contexts,new ExplorationAiInputAdapter(new ExplorationJson(mapper),contract),contract,client);
    JsonNode context;
    @BeforeEach void load()throws Exception{context=mapper.readTree(Files.readString(Path.of("datasets/exploration-ai-contract/cases.json"))).path("adapterCases").get(0).path("context");}
    ResultContextService.LockedInput locked(JsonNode value){return new ResultContextService.LockedInput(10,"CONSULTATION",value);}
    @Test void verifiesOwnedContextBeforeAndAfterTransport(){when(contexts.readForAdapter(1,10,"CONSULTATION")).thenReturn(locked(context));var expected=new ExplorationEvidenceClient.Outcome("UNAVAILABLE","EVIDENCE_BINDING_DISABLED",false,null);when(client.prepare(any())).thenReturn(expected);assertThat(service.prepareWithEvidence(1,10,"CONSULTATION")).isSameAs(expected);var order=inOrder(contexts,client);order.verify(contexts).readForAdapter(1,10,"CONSULTATION");order.verify(client).prepare(any());order.verify(contexts).readForAdapter(1,10,"CONSULTATION");}
    @Test void deniesBeforeAnyTransportIfOwnershipReadFails(){when(contexts.readForAdapter(1,10,"CONSULTATION")).thenThrow(new IllegalStateException("denied"));assertThatThrownBy(()->service.prepareWithEvidence(1,10,"CONSULTATION")).isInstanceOf(IllegalStateException.class);verifyNoInteractions(client);}
    @Test void rejectsAfterTransportIfAccountOrContextNoLongerAvailable(){when(contexts.readForAdapter(1,10,"CONSULTATION")).thenReturn(locked(context)).thenThrow(new IllegalStateException("deleted"));when(client.prepare(any())).thenReturn(new ExplorationEvidenceClient.Outcome("BOUND",null,false,null));assertThatThrownBy(()->service.prepareWithEvidence(1,10,"CONSULTATION")).isInstanceOf(IllegalStateException.class);}
    @Test void changedContextDiscardsPreparedResponse(){ObjectNode changed=context.deepCopy();changed.put("changed",true);when(contexts.readForAdapter(1,10,"CONSULTATION")).thenReturn(locked(context),locked(changed));when(client.prepare(any())).thenReturn(new ExplorationEvidenceClient.Outcome("BOUND",null,false,null));var out=service.prepareWithEvidence(1,10,"CONSULTATION");assertThat(out.reasonCode()).isEqualTo("SOURCE_CONTEXT_CHANGED");assertThat(out.request()).isNull();assertThat(out.readyForGeneration()).isFalse();}
}