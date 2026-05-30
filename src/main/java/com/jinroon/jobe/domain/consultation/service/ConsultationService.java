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

    public List<ConsultationSession> findSessionsForUser(Long requestedUserId, Long currentUserId) {
        requireOwner(requestedUserId, currentUserId);
        return findSessionsByUser(requestedUserId);
    }

    public ConsultationSession getSession(Long sessionId) {
        return get(consultationSessionRepository, sessionId, ErrorCode.CONSULTATION_SESSION_NOT_FOUND);
    }

    public ConsultationSession getSessionForUser(Long sessionId, Long userId) {
        ConsultationSession session = getSession(sessionId);
        requireOwner(session.getUserId(), userId);
        return session;
    }

    public List<ConsultationLog> findLogs(Long sessionId) {
        return consultationLogRepository.findByConsultationSessionIdOrderByCreatedAtAsc(sessionId);
    }

    public List<ConsultationLog> findLogsForUser(Long sessionId, Long userId) {
        getSessionForUser(sessionId, userId);
        return findLogs(sessionId);
    }

    public ConsultationLog getLog(Long logId) {
        return get(consultationLogRepository, logId, ErrorCode.CONSULTATION_LOG_NOT_FOUND);
    }

    public ConsultationLog getLogForUser(Long logId, Long userId) {
        ConsultationLog log = getLog(logId);
        getSessionForUser(log.getConsultationSessionId(), userId);
        return log;
    }

    @Transactional
    public ConsultationSession createSession(Map<String, Object> values) {
        return consultationSessionRepository.save(EntityFormMapper.create(ConsultationSession.class, values));
    }

    @Transactional
    public ConsultationSession createSessionForUser(Map<String, Object> values, Long userId) {
        values.put("userId", userId);
        return createSession(values);
    }

    @Transactional
    public ConsultationSession updateSession(Long sessionId, Map<String, Object> values) {
        ConsultationSession session = getSession(sessionId);
        EntityFormMapper.apply(session, values);
        return session;
    }

    @Transactional
    public ConsultationSession updateSessionForUser(Long sessionId, Long userId, Map<String, Object> values) {
        ConsultationSession session = getSessionForUser(sessionId, userId);
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
    public ConsultationSession endSessionForUser(Long sessionId, Long userId) {
        ConsultationSession session = getSessionForUser(sessionId, userId);
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

    @Transactional
    public ConsultationLog createLogForUser(Map<String, Object> values, Long userId) {
        Long sessionId = ((Number) values.get("consultationSessionId")).longValue();
        ConsultationSession session = getSessionForUser(sessionId, userId);
        if (session.isEnded()) {
            throw new CustomException(ErrorCode.CONSULTATION_SESSION_ALREADY_ENDED);
        }
        return consultationLogRepository.save(EntityFormMapper.create(ConsultationLog.class, values));
    }

    private void requireOwner(Long ownerId, Long userId) {
        if (!ownerId.equals(userId)) {
            throw new CustomException(ErrorCode.FORBIDDEN);
        }
    }
}
