package com.jinroon.jobe.domain.consultation.service;

import static com.jinroon.jobe.global.common.entity.EntityLookup.get;

import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import com.jinroon.jobe.domain.consultation.entity.ConsultationSession;
import com.jinroon.jobe.domain.consultation.repository.ConsultationLogRepository;
import com.jinroon.jobe.domain.consultation.repository.ConsultationSessionRepository;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ConsultationService {

    private final ConsultationSessionRepository consultationSessionRepository;
    private final ConsultationLogRepository consultationLogRepository;

    public List<ConsultationSession> findSessionsByUser(Long userId) {
        return consultationSessionRepository.findByUserId(userId);
    }

    public ConsultationSession getSession(Long sessionId) {
        return get(consultationSessionRepository, sessionId, ErrorCode.CONSULTATION_SESSION_NOT_FOUND);
    }

    public List<ConsultationLog> findLogs(Long sessionId) {
        return consultationLogRepository.findByConsultationSessionIdOrderByCreatedAtAsc(sessionId);
    }

    public ConsultationLog getLog(Long logId) {
        return get(consultationLogRepository, logId, ErrorCode.CONSULTATION_LOG_NOT_FOUND);
    }

    @Transactional
    public ConsultationSession createSession(Map<String, Object> values) {
        return consultationSessionRepository.save(EntityFormMapper.create(ConsultationSession.class, values));
    }

    @Transactional
    public ConsultationSession updateSession(Long sessionId, Map<String, Object> values) {
        ConsultationSession session = getSession(sessionId);
        EntityFormMapper.apply(session, values);
        return session;
    }

    @Transactional
    public ConsultationSession endSession(Long sessionId) {
        ConsultationSession session = getSession(sessionId);
        if (!session.isEnded()) {
            session.end();
        }
        return session;
    }

    @Transactional
    public ConsultationLog createLog(Map<String, Object> values) {
        Long sessionId = ((Number) values.get("consultationSessionId")).longValue();
        ConsultationSession session = getSession(sessionId);
        if (session.isEnded()) {
            throw new CustomException(ErrorCode.CONSULTATION_SESSION_ALREADY_ENDED);
        }
        return consultationLogRepository.save(EntityFormMapper.create(ConsultationLog.class, values));
    }
}
