package com.jinroon.jobe.domain.exploration.consultation;

import java.util.List;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonInclude;

/** Public consumer contract. Internal prompts, ownership keys and approval hashes are excluded. */
public final class ConsultationDtos {
    private ConsultationDtos() {}
    public enum SourceKind { RESULT_SNAPSHOT, METRIC_DEFINITION, MAJOR_PROFILE, ACTIVITY_CATALOG, ANSWER_POLICY }
    public enum EvidenceStatus { GROUNDED, INSUFFICIENT }
    public record Source(SourceKind kind,String title,String version) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Message(long id,String role,String content,String createdAt,List<Source> sources,EvidenceStatus evidenceStatus) {
        public Message { sources=List.copyOf(sources); }
    }
    public record Pending(String retryKey,String content,String status,String nextRetryAt) {}
    public record Session(long consultationId,long resultId,List<Message> messages,
            @JsonInclude(JsonInclude.Include.NON_NULL) Pending pendingRequest) {
        public Session { messages=List.copyOf(messages); }
        public Session(long consultationId,long resultId,List<Message> messages){this(consultationId,resultId,messages,null);}
    }
    public record Answer(Message userMessage,Message assistantMessage) {}
    public static Message message(JsonNode value){
        var sources=new ArrayList<Source>();
        for(JsonNode s:value.path("sources"))sources.add(new Source(SourceKind.valueOf(s.path("kind").asText()),s.path("title").asText(),s.path("version").asText()));
        String evidence=value.path("evidenceStatus").textValue();
        return new Message(value.path("id").asLong(),value.path("role").asText(),value.path("content").asText(),value.path("createdAt").asText(),sources,evidence==null?null:EvidenceStatus.valueOf(evidence));
    }
}
