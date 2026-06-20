package com.jinroon.jobe.domain.consultation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jinroon.jobe.domain.consultation.dto.request.ConsultationMessageRequest;
import com.jinroon.jobe.domain.consultation.dto.response.ConsultationMessageResponse;
import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import com.jinroon.jobe.domain.consultation.entity.ConsultationSession;
import com.jinroon.jobe.domain.consultation.repository.ConsultationLogRepository;
import com.jinroon.jobe.domain.consultation.repository.ConsultationSessionRepository;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.ConsultationChatRequest;
import com.jinroon.jobe.global.client.dto.response.ConsultationChatResponse;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ConsultationServiceTest {

    @Mock
    private ConsultationSessionRepository sessionRepository;

    @Mock
    private ConsultationLogRepository logRepository;

    @Mock
    private DiagnosisResultRepository diagnosisResultRepository;

    @Mock
    private ResultMajorScoreRepository resultMajorScoreRepository;

    @Mock
    private MajorRepository majorRepository;

    @Mock
    private MajorWeeklyPlanRepository planRepository;

    @Mock
    private MajorWeeklyPlanItemRepository planItemRepository;

    @Mock
    private AiServiceClient aiServiceClient;

    private ConsultationService service;

    @BeforeEach
    void setUp() {
        service = new ConsultationService(
                sessionRepository,
                logRepository,
                diagnosisResultRepository,
                resultMajorScoreRepository,
                majorRepository,
                planRepository,
                planItemRepository,
                aiServiceClient
        );
    }

    @Test
    void createMessageWithAiReplyStoresUserAndAssistantLogs() {
        ConsultationSession session = session(1L, 7L, null, false);
        ConsultationLog userLog = log(10L, 1L, "user", "question");
        ConsultationLog assistantLog = log(11L, 1L, "assistant", "answer");

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(logRepository.save(any(ConsultationLog.class)))
                .thenReturn(userLog)
                .thenReturn(assistantLog);
        when(logRepository.findByConsultationSessionIdOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(userLog));
        when(diagnosisResultRepository.findTopByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(Optional.empty());
        when(aiServiceClient.getConsultationChat(any(ConsultationChatRequest.class)))
                .thenReturn(new ConsultationChatResponse("answer", "consultation-chat-v1.0.0", "request-1"));

        ConsultationMessageResponse response = service.createMessageWithAiReplyForUser(
                1L,
                new ConsultationMessageRequest("question"),
                7L
        );

        assertThat(response.userLog()).isSameAs(userLog);
        assertThat(response.assistantLog()).isSameAs(assistantLog);
        assertThat(response.contextUsed().diagnosisResultId()).isNull();
        assertThat(response.contextUsed().historyMessageCount()).isEqualTo(1);

        ArgumentCaptor<ConsultationLog> logCaptor = ArgumentCaptor.forClass(ConsultationLog.class);
        verify(logRepository, org.mockito.Mockito.times(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues())
                .extracting(log -> log.getRole().name())
                .containsExactly("user", "assistant");
    }

    @Test
    void createMessageRejectsOtherUsersSession() {
        ConsultationSession session = session(1L, 7L, null, false);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.createMessageWithAiReplyForUser(
                1L,
                new ConsultationMessageRequest("question"),
                99L
        ))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(logRepository, never()).save(any());
        verify(aiServiceClient, never()).getConsultationChat(any());
    }

    @Test
    void createMessageRejectsEndedSession() {
        ConsultationSession session = session(1L, 7L, null, true);
        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service.createMessageWithAiReplyForUser(
                1L,
                new ConsultationMessageRequest("question"),
                7L
        ))
                .isInstanceOf(CustomException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.CONSULTATION_SESSION_ALREADY_ENDED);

        verify(logRepository, never()).save(any());
        verify(aiServiceClient, never()).getConsultationChat(any());
    }

    @Test
    void createMessageStoresFallbackAssistantLogWhenAiServiceReturnsNull() {
        ConsultationSession session = session(1L, 7L, null, false);
        ConsultationLog userLog = log(10L, 1L, "user", "recommend certificates");
        ConsultationLog assistantLog = log(11L, 1L, "assistant", "fallback");

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(logRepository.save(any(ConsultationLog.class)))
                .thenReturn(userLog)
                .thenReturn(assistantLog);
        when(logRepository.findByConsultationSessionIdOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(userLog));
        when(diagnosisResultRepository.findTopByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(Optional.empty());
        when(aiServiceClient.getConsultationChat(any(ConsultationChatRequest.class))).thenReturn(null);

        ConsultationMessageResponse response = service.createMessageWithAiReplyForUser(
                1L,
                new ConsultationMessageRequest("recommend certificates"),
                7L
        );

        assertThat(response.assistantLog()).isSameAs(assistantLog);
        ArgumentCaptor<ConsultationLog> logCaptor = ArgumentCaptor.forClass(ConsultationLog.class);
        verify(logRepository, org.mockito.Mockito.times(2)).save(logCaptor.capture());
        assertThat(logCaptor.getAllValues().get(1).getContent()).contains("진단 결과");
    }

    private ConsultationSession session(Long id, Long userId, Long diagnosisResultId, boolean ended) {
        ConsultationSession session = newEntity(ConsultationSession.class);
        ReflectionTestUtils.setField(session, "id", id);
        ReflectionTestUtils.setField(session, "userId", userId);
        ReflectionTestUtils.setField(session, "diagnosisResultId", diagnosisResultId);
        ReflectionTestUtils.setField(session, "startedAt", LocalDateTime.now());
        if (ended) {
            ReflectionTestUtils.setField(session, "endedAt", LocalDateTime.now());
        }
        return session;
    }

    private ConsultationLog log(Long id, Long sessionId, String role, String content) {
        ConsultationLog log = newEntity(ConsultationLog.class);
        ReflectionTestUtils.setField(log, "id", id);
        ReflectionTestUtils.setField(log, "consultationSessionId", sessionId);
        ReflectionTestUtils.setField(log, "role", com.jinroon.jobe.domain.consultation.enums.ConsultationEnums.ConsultationRole.valueOf(role));
        ReflectionTestUtils.setField(log, "content", content);
        return log;
    }

    private <T> T newEntity(Class<T> type) {
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            return constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
