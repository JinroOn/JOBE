package com.jinroon.jobe.domain.exploration.consultation;

import java.util.List;

/** Public consumer contract. Internal prompts, ownership keys and approval hashes are excluded. */
public final class ConsultationDtos {
    private ConsultationDtos() {}
    public enum SourceKind { RESULT_SNAPSHOT, METRIC_DEFINITION, MAJOR_PROFILE, ACTIVITY_CATALOG, ANSWER_POLICY }
    public enum EvidenceStatus { GROUNDED, INSUFFICIENT }
    public record Source(SourceKind kind,String title,String version) {}
    public record Message(long id,String role,String content,String createdAt,List<Source> sources,EvidenceStatus evidenceStatus) {
        public Message { sources=List.copyOf(sources); }
    }
    public record Session(long consultationId,long resultId,List<Message> messages) {
        public Session { messages=List.copyOf(messages); }
    }
    public record Answer(Message userMessage,Message assistantMessage) {}
}
