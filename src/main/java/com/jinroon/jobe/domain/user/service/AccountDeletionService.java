package com.jinroon.jobe.domain.user.service;

import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Deletes current MySQL personal data atomically; never deletes shared catalogs or invents retention periods. */
@Service
@RequiredArgsConstructor
public class AccountDeletionService {
    private final UserRepository users;
    private final JdbcTemplate jdbc;

    @Transactional
    public void delete(Long userId) {
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        user.withdraw();
        users.flush();

        // Same MySQL transaction as account deletion; survives remote outages.
        jdbc.update("INSERT IGNORE INTO exploration_ai_erasure_outbox(owner_key) SELECT owner_key FROM exploration_ai_owner WHERE user_id=?",userId);
        jdbc.update("DELETE FROM exploration_ai_owner WHERE user_id=?",userId);

        jdbc.update("DELETE i FROM major_weekly_plan_items i JOIN major_weekly_plans p ON i.weekly_plan_id=p.id "
                + "JOIN diagnosis_results r ON p.diagnosis_result_id=r.id WHERE r.user_id=?", userId);
        jdbc.update("DELETE i FROM major_weekly_plan_risk_notes i JOIN major_weekly_plans p ON i.weekly_plan_id=p.id "
                + "JOIN diagnosis_results r ON p.diagnosis_result_id=r.id WHERE r.user_id=?", userId);
        jdbc.update("UPDATE major_weekly_plans SET parent_plan_id=NULL WHERE diagnosis_result_id IN "
                + "(SELECT id FROM diagnosis_results WHERE user_id=?)", userId);
        jdbc.update("DELETE p FROM major_weekly_plans p JOIN diagnosis_results r ON p.diagnosis_result_id=r.id WHERE r.user_id=?", userId);
        jdbc.update("DELETE l FROM consultation_logs l JOIN consultation_sessions s ON l.consultation_session_id=s.id WHERE s.user_id=?", userId);
        jdbc.update("DELETE FROM consultation_sessions WHERE user_id=?", userId);
        jdbc.update("DELETE m FROM result_major_scores m JOIN diagnosis_results r ON m.diagnosis_result_id=r.id WHERE r.user_id=?", userId);
        jdbc.update("DELETE FROM diagnosis_results WHERE user_id=?", userId);
        for (String table : new String[]{"diagnosis_exam_answers", "diagnosis_essay_answers", "competency_eval_results", "tendency_eval_results"}) {
            jdbc.update("DELETE a FROM " + table + " a JOIN diagnosis_sessions s ON a.diagnosis_session_id=s.id WHERE s.user_id=?", userId);
        }
        jdbc.update("DELETE FROM diagnosis_sessions WHERE user_id=?", userId);

        jdbc.update("DELETE a FROM exploration_answer_revision a JOIN exploration_issued_question q ON a.issued_id=q.id "
                + "JOIN exploration_session s ON q.session_id=s.id WHERE s.user_id=?", userId);
        jdbc.update("DELETE r FROM exploration_result r JOIN exploration_session s ON r.session_id=s.id WHERE s.user_id=?", userId);
        jdbc.update("DELETE q FROM exploration_issued_question q JOIN exploration_session s ON q.session_id=s.id WHERE s.user_id=?", userId);
        jdbc.update("DELETE FROM exploration_idempotency WHERE user_id=?", userId);
        jdbc.update("DELETE FROM exploration_session WHERE user_id=?", userId);
        for (String table : new String[]{"sessions", "user_favorites", "user_consents"}) {
            jdbc.update("DELETE FROM " + table + " WHERE user_id=?", userId);
        }
        jdbc.update("DELETE FROM email_verifications WHERE email=?", user.getEmail());
        jdbc.update("DELETE FROM password_reset_tokens WHERE email=?", user.getEmail());
        // Preserve shared notices while removing the former administrator's personal association.
        jdbc.update("UPDATE notices SET created_by=0 WHERE created_by=?", userId);
        users.delete(user);
        users.flush();
    }
}
