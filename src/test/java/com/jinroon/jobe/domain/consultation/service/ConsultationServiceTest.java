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
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.repository.DiagnosisSessionRepository;
import com.jinroon.jobe.domain.diagnosis.service.DiagnosisProfileScoringService;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanItemRepository;
import com.jinroon.jobe.domain.plan.repository.MajorWeeklyPlanRepository;
import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.repository.DiagnosisResultRepository;
import com.jinroon.jobe.domain.result.repository.ResultMajorScoreRepository;
import com.jinroon.jobe.global.client.AiServiceClient;
import com.jinroon.jobe.global.client.dto.request.ConsultationChatRequest;
import com.jinroon.jobe.global.client.dto.response.ConsultationChatResponse;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private DiagnosisSessionRepository diagnosisSessionRepository;

    @Mock
    private ResultMajorScoreRepository resultMajorScoreRepository;

    @Mock
    private MajorRepository majorRepository;

    @Mock
    private MajorWeeklyPlanRepository planRepository;

    @Mock
    private MajorWeeklyPlanItemRepository planItemRepository;

    private DiagnosisProfileScoringService diagnosisProfileScoringService;

    @Mock
    private AiServiceClient aiServiceClient;

    private ConsultationService service;

    @BeforeEach
    void setUp() {
        diagnosisProfileScoringService = new DiagnosisProfileScoringService(new ObjectMapper());
        service = new ConsultationService(
                sessionRepository,
                logRepository,
                diagnosisResultRepository,
                diagnosisSessionRepository,
                resultMajorScoreRepository,
                majorRepository,
                planRepository,
                planItemRepository,
                diagnosisProfileScoringService,
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

        ArgumentCaptor<ConsultationChatRequest> requestCaptor = ArgumentCaptor.forClass(ConsultationChatRequest.class);
        verify(aiServiceClient).getConsultationChat(requestCaptor.capture());
        assertThat(requestCaptor.getValue().hasDiagnosisContext()).isFalse();
        assertThat(requestCaptor.getValue().diagnosisContext()).isNull();
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
        assertThat(logCaptor.getAllValues().get(1).getContent())
                .contains("진로온", "전공 추천", "진단", "개인 맞춤");
    }

    @Test
    void createMessageIncludesDiagnosisAndTopMajorContextWhenDiagnosisExists() {
        ConsultationSession session = session(1L, 7L, 5L, false);
        DiagnosisResult result = diagnosisResult(5L, 7L);
        ResultMajorScore score = resultMajorScore(20L, 5L, 100L);
        Major major = major(100L, "컴퓨터공학과");
        ConsultationLog userLog = log(10L, 1L, "user", "내가 따면 좋을 자격증 추천해줘");
        ConsultationLog assistantLog = log(11L, 1L, "assistant", "answer");

        when(sessionRepository.findById(1L)).thenReturn(Optional.of(session));
        when(logRepository.save(any(ConsultationLog.class)))
                .thenReturn(userLog)
                .thenReturn(assistantLog);
        when(logRepository.findByConsultationSessionIdOrderByCreatedAtAsc(1L))
                .thenReturn(List.of(userLog));
        when(diagnosisResultRepository.findById(5L)).thenReturn(Optional.of(result));
        when(diagnosisSessionRepository.findById(30L)).thenReturn(Optional.of(diagnosisSession("""
                {
                  "grade": "1학년",
                  "dreamJob": "AI 데이터 사이언티스트",
                  "studyHours": 4.5,
                  "selectedSubjects": ["정보/코딩"],
                  "learningStyle": "practice",
                  "aspiration": "데이터로 문제를 해결하고 싶다"
                }
                """)));
        when(resultMajorScoreRepository.findByDiagnosisResultIdOrderByRankAsc(5L)).thenReturn(List.of(score));
        when(majorRepository.findAllById(any())).thenReturn(List.of(major));
        when(planRepository.findByDiagnosisResultId(5L)).thenReturn(List.of());
        when(aiServiceClient.getConsultationChat(any(ConsultationChatRequest.class)))
                .thenReturn(new ConsultationChatResponse("answer", "consultation-chat-v1.0.0", "request-2"));

        ConsultationMessageResponse response = service.createMessageWithAiReplyForUser(
                1L,
                new ConsultationMessageRequest("내가 따면 좋을 자격증 추천해줘"),
                7L
        );

        assertThat(response.contextUsed().diagnosisResultId()).isEqualTo(5L);
        assertThat(response.contextUsed().topMajorNames()).containsExactly("컴퓨터공학과");
        assertThat(response.contextUsed().weaknessFocus()).containsExactly("communicationScore");

        ArgumentCaptor<ConsultationChatRequest> requestCaptor = ArgumentCaptor.forClass(ConsultationChatRequest.class);
        verify(aiServiceClient).getConsultationChat(requestCaptor.capture());
        ConsultationChatRequest request = requestCaptor.getValue();
        assertThat(request.hasDiagnosisContext()).isTrue();
        assertThat(request.diagnosisContext()).isNotNull();
        assertThat(request.diagnosisContext().diagnosisResultId()).isEqualTo(5L);
        assertThat(request.diagnosisContext().topMajors())
                .extracting(ConsultationChatRequest.TopMajor::majorName)
                .containsExactly("컴퓨터공학과");
        assertThat(request.diagnosisContext().profileContext()).isNotNull();
        assertThat(request.diagnosisContext().profileContext().dreamJob()).isEqualTo("AI 데이터 사이언티스트");
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

    private DiagnosisResult diagnosisResult(Long id, Long userId) {
        DiagnosisResult result = newEntity(DiagnosisResult.class);
        ReflectionTestUtils.setField(result, "id", id);
        ReflectionTestUtils.setField(result, "userId", userId);
        ReflectionTestUtils.setField(result, "diagnosisSessionId", 30L);
        ReflectionTestUtils.setField(result, "competencyVector", "{}");
        ReflectionTestUtils.setField(result, "tendencyVector", "{}");
        ReflectionTestUtils.setField(result, "aiComment", "소프트웨어 구현 역량이 강점입니다.");
        ReflectionTestUtils.setField(result, "weaknessFocus", "communicationScore");
        return result;
    }

    private ResultMajorScore resultMajorScore(Long id, Long diagnosisResultId, Long majorId) {
        ResultMajorScore score = newEntity(ResultMajorScore.class);
        ReflectionTestUtils.setField(score, "id", id);
        ReflectionTestUtils.setField(score, "diagnosisResultId", diagnosisResultId);
        ReflectionTestUtils.setField(score, "majorId", majorId);
        ReflectionTestUtils.setField(score, "rank", 1);
        ReflectionTestUtils.setField(score, "finalScore", 88.0f);
        ReflectionTestUtils.setField(score, "competencyScore", 90.0f);
        ReflectionTestUtils.setField(score, "tendencyScore", 85.0f);
        ReflectionTestUtils.setField(score, "failed", false);
        ReflectionTestUtils.setField(score, "strengths", "구현 역량");
        ReflectionTestUtils.setField(score, "weaknesses", "의사소통");
        ReflectionTestUtils.setField(score, "recommendationReason", "소프트웨어 구현 역량과 잘 맞습니다.");
        return score;
    }

    private Major major(Long id, String name) {
        Major major = newEntity(Major.class);
        ReflectionTestUtils.setField(major, "id", id);
        ReflectionTestUtils.setField(major, "name", name);
        ReflectionTestUtils.setField(major, "category", "공학");
        ReflectionTestUtils.setField(major, "description", "컴퓨터 시스템과 소프트웨어를 학습합니다.");
        ReflectionTestUtils.setField(major, "careerPaths", "소프트웨어 개발자, 데이터 엔지니어");
        return major;
    }

    private DiagnosisSession diagnosisSession(String inputSnapshot) {
        DiagnosisSession session = newEntity(DiagnosisSession.class);
        ReflectionTestUtils.setField(session, "id", 30L);
        ReflectionTestUtils.setField(session, "inputSnapshot", inputSnapshot);
        return session;
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
