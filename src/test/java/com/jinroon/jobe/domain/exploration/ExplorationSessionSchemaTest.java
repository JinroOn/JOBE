package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jinroon.jobe.domain.exploration.entity.ExplorationAnswerRevision;
import com.jinroon.jobe.domain.exploration.entity.ExplorationIdempotency;
import com.jinroon.jobe.domain.exploration.entity.ExplorationIssuedQuestion;
import com.jinroon.jobe.domain.exploration.entity.ExplorationResult;
import com.jinroon.jobe.domain.exploration.entity.ExplorationSession;
import com.jinroon.jobe.domain.exploration.entity.ExplorationSession.VersionBundle;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationResultStatus;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import com.jinroon.jobe.domain.exploration.repository.ExplorationAnswerRevisionRepository;
import com.jinroon.jobe.domain.exploration.repository.ExplorationIdempotencyRepository;
import com.jinroon.jobe.domain.exploration.repository.ExplorationIssuedQuestionRepository;
import com.jinroon.jobe.domain.exploration.repository.ExplorationResultRepository;
import com.jinroon.jobe.domain.exploration.repository.ExplorationSessionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * V2(회차·발급 문항·답변 이력·멱등 저장소·결과)의 유일키와 잠금 동작을 실제 MySQL로 확인한다(D07).
 * 동시성 확인을 위해 실제로 커밋하므로 테스트 클래스 트랜잭션을 쓰지 않고, 만든 데이터를 직접 지운다.
 * 로컬 MySQL(docker compose up -d mysql)이 필요하다.
 */
@SpringBootTest
class ExplorationSessionSchemaTest {

    @Autowired
    private ExplorationSessionRepository sessionRepository;

    @Autowired
    private ExplorationIssuedQuestionRepository issuedRepository;

    @Autowired
    private ExplorationAnswerRevisionRepository answerRepository;

    @Autowired
    private ExplorationIdempotencyRepository idempotencyRepository;

    @Autowired
    private ExplorationResultRepository resultRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private final String runId = Long.toHexString(ThreadLocalRandom.current().nextLong());
    // 실제 사용자 ID와 겹치지 않도록 큰 값을 쓴다(users FK는 없다).
    private final long userId = 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    private final List<Long> catalogIds = new ArrayList<>();
    private Long rulesetId;
    private VersionBundle versions;

    @BeforeEach
    void setUp() {
        long question = insertCatalog("QUESTION_CATALOG");
        long option = insertCatalog("OPTION_CATALOG");
        long variant = insertCatalog("OPTION_VARIANT");
        long mapping = insertCatalog("MAJOR_MAPPING");
        long semantic = insertCatalog("SEMANTIC_CONTRACT");
        jdbcTemplate.update(
                "INSERT INTO exploration_ruleset (version, content_status, implementation_review_status, "
                        + "domain_review_status, rules_json, content_hash) "
                        + "VALUES (?, 'REVIEWED', 'APPROVED_FOR_IMPLEMENTATION', 'PENDING', JSON_OBJECT(), ?)",
                "TEST_RULESET_" + runId, "0".repeat(64));
        rulesetId = jdbcTemplate.queryForObject(
                "SELECT id FROM exploration_ruleset WHERE version = ?", Long.class, "TEST_RULESET_" + runId);
        versions = new VersionBundle(question, option, variant, mapping, rulesetId, semantic);
    }

    @AfterEach
    void cleanUp() {
        List<Long> sessionIds = jdbcTemplate.queryForList(
                "SELECT id FROM exploration_session WHERE user_id = ?", Long.class, userId);
        for (Long sessionId : sessionIds) {
            jdbcTemplate.update("DELETE FROM exploration_result WHERE session_id = ?", sessionId);
            jdbcTemplate.update("DELETE a FROM exploration_answer_revision a JOIN exploration_issued_question q "
                    + "ON a.issued_id = q.id WHERE q.session_id = ?", sessionId);
            jdbcTemplate.update("DELETE FROM exploration_issued_question WHERE session_id = ?", sessionId);
        }
        jdbcTemplate.update("DELETE FROM exploration_session WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM exploration_idempotency WHERE user_id = ?", userId);
        jdbcTemplate.update("DELETE FROM exploration_ruleset WHERE id = ?", rulesetId);
        catalogIds.forEach(id -> jdbcTemplate.update("DELETE FROM exploration_catalog WHERE id = ?", id));
    }

    // ---- 회차: 사용자당 진행 중 1개 ----

    @Test
    void secondInProgressSessionForSameUserIsRejected() {
        sessionRepository.saveAndFlush(newSession());

        assertThatThrownBy(() -> sessionRepository.saveAndFlush(newSession()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_exploration_session_active_user");
    }

    @Test
    void completedAndAbandonedSessionsAreKeptAndNewOneCanStart() {
        ExplorationSession first = sessionRepository.saveAndFlush(newSession());
        transactionTemplate.executeWithoutResult(status -> {
            ExplorationSession locked = sessionRepository.findByIdForUpdate(first.getId()).orElseThrow();
            locked.abandon(LocalDateTime.now());
        });
        ExplorationSession second = sessionRepository.saveAndFlush(newSession());
        transactionTemplate.executeWithoutResult(status -> {
            ExplorationSession locked = sessionRepository.findByIdForUpdate(second.getId()).orElseThrow();
            locked.complete(LocalDateTime.now());
        });

        ExplorationSession third = sessionRepository.saveAndFlush(newSession());

        assertThat(sessionRepository.findByUserIdOrderByStartedAtDesc(userId)).hasSize(3);
        assertThat(sessionRepository.findFirstByUserIdAndStatus(userId, ExplorationSessionStatus.in_progress))
                .get().extracting(ExplorationSession::getId).isEqualTo(third.getId());
    }

    @Test
    void concurrentStartsLeaveExactlyOneInProgressSession() throws Exception {
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> attemptStart(() -> {
                firstInserted.countDown();
                // 두 번째 요청이 같은 유일키에서 대기하도록 커밋을 잠시 미룬다.
                awaitQuietly(secondStarted);
                sleepQuietly(300);
            }));
            assertThat(firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> second = executor.submit(() -> {
                secondStarted.countDown();
                return attemptStart(() -> { });
            });

            List<Boolean> outcomes = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertThat(outcomes).containsExactlyInAnyOrder(true, false);
            Integer inProgress = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM exploration_session WHERE user_id = ? AND status = 'in_progress'",
                    Integer.class, userId);
            assertThat(inProgress).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- 잠금과 revision ----

    @Test
    void lockedSessionMakesWaitingRequestSeeLatestRevision() throws Exception {
        ExplorationSession session = sessionRepository.saveAndFlush(newSession());
        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> writer = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                ExplorationSession s = sessionRepository.findByIdForUpdate(session.getId()).orElseThrow();
                locked.countDown();
                sleepQuietly(300);
                s.increaseRevision();
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            // 두 번째 요청은 첫 요청이 커밋할 때까지 기다린 뒤 최신 revision(1)을 본다.
            // 클라이언트가 보낸 expectedRevision=0은 이제 맞지 않으므로 서비스는 409 STALE_REVISION을 주게 된다.
            Long seenRevision = executor.submit(() -> transactionTemplate.execute(status ->
                    sessionRepository.findByIdForUpdate(session.getId()).orElseThrow().getRevision()))
                    .get(10, TimeUnit.SECONDS);
            writer.get(10, TimeUnit.SECONDS);

            assertThat(seenRevision).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    // ---- 발급 문항 ----

    @Test
    void issuedOrdinalIsUniquePerSession() {
        ExplorationSession session = sessionRepository.saveAndFlush(newSession());
        issuedRepository.saveAndFlush(issue(session.getId(), "INT-HUM-01", 1));

        assertThatThrownBy(() -> issuedRepository.saveAndFlush(issue(session.getId(), "INT-SCI-01", 1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_exploration_issued_ordinal");
        assertThat(issuedRepository.findMaxOrdinal(session.getId())).isEqualTo(1);
    }

    // ---- 답변: 발급 문항당 최신 답변 최대 1개 ----

    @Test
    void secondCurrentAnswerForSameIssuedIsRejected() {
        ExplorationIssuedQuestion issued = issuedForNewSession();
        answerRepository.saveAndFlush(answer(issued.getId(), 1, "AGREE", "m1"));

        assertThatThrownBy(() -> answerRepository.saveAndFlush(answer(issued.getId(), 2, "DISAGREE", "m2")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_exploration_answer_current");
    }

    @Test
    void releasingPreviousAnswerThenInsertingKeepsOneCurrentAndHistory() {
        ExplorationIssuedQuestion issued = issuedForNewSession();
        answerRepository.saveAndFlush(answer(issued.getId(), 1, "AGREE", "m1"));

        transactionTemplate.executeWithoutResult(status -> {
            ExplorationAnswerRevision previous = answerRepository.findByIssuedIdAndCurrentTrue(issued.getId())
                    .orElseThrow();
            previous.release();
            answerRepository.flush();
            answerRepository.save(answer(issued.getId(), 2, "DISAGREE", "m2"));
        });

        ExplorationAnswerRevision current = answerRepository.findByIssuedIdAndCurrentTrue(issued.getId())
                .orElseThrow();
        assertThat(current.getAnswerRevision()).isEqualTo(2);
        assertThat(current.getOptionCode()).isEqualTo("DISAGREE");
        assertThat(answerRepository.findFirstByIssuedIdOrderByAnswerRevisionDesc(issued.getId()))
                .get().extracting(ExplorationAnswerRevision::getAnswerRevision).isEqualTo(2);
        Integer total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM exploration_answer_revision WHERE issued_id = ?", Integer.class, issued.getId());
        assertThat(total).isEqualTo(2);
    }

    @Test
    void failedAnswerUpdateRollsBackRelease() {
        ExplorationIssuedQuestion issued = issuedForNewSession();
        answerRepository.saveAndFlush(answer(issued.getId(), 1, "AGREE", "m1"));

        // 같은 revision 번호로 insert → 실패 → 이전 답변 해제도 롤백되어야 한다(D07 2절).
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            answerRepository.findByIssuedIdAndCurrentTrue(issued.getId()).orElseThrow().release();
            answerRepository.flush();
            answerRepository.saveAndFlush(answer(issued.getId(), 1, "DISAGREE", "m2"));
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(answerRepository.findByIssuedIdAndCurrentTrue(issued.getId()))
                .get().extracting(ExplorationAnswerRevision::getOptionCode).isEqualTo("AGREE");
    }

    @Test
    void clientMutationIdIsUniquePerIssued() {
        ExplorationIssuedQuestion issued = issuedForNewSession();
        answerRepository.saveAndFlush(answer(issued.getId(), 1, "AGREE", "same-mutation"));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            answerRepository.findByIssuedIdAndCurrentTrue(issued.getId()).orElseThrow().release();
            answerRepository.flush();
            answerRepository.saveAndFlush(answer(issued.getId(), 2, "DISAGREE", "same-mutation"));
        })).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_exploration_answer_mutation");
        assertThat(answerRepository.findByIssuedIdAndClientMutationId(issued.getId(), "same-mutation")).isPresent();
    }

    // ---- 멱등 저장소 ----

    @Test
    void idempotencyKeyIsUniquePerUserOperationAndResource() {
        byte[] hash = new byte[32];
        idempotencyRepository.saveAndFlush(ExplorationIdempotency.reserve(
                userId, "START_SESSION", ExplorationIdempotency.NO_RESOURCE, "key-1", hash));

        assertThatThrownBy(() -> idempotencyRepository.saveAndFlush(ExplorationIdempotency.reserve(
                userId, "START_SESSION", ExplorationIdempotency.NO_RESOURCE, "key-1", hash)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 같은 키라도 작업이나 대상이 다르면 별개다.
        idempotencyRepository.saveAndFlush(ExplorationIdempotency.reserve(userId, "ABANDON_SESSION", 1L, "key-1", hash));

        ExplorationIdempotency stored = idempotencyRepository
                .findByUserIdAndOperationAndResourceIdAndIdempotencyKey(userId, "START_SESSION", 0L, "key-1")
                .orElseThrow();
        assertThat(stored.hasSameRequest(hash)).isTrue();
        assertThat(stored.getResponseStatus()).isNull();
    }

    @Test
    void idempotencySuccessResponseMustBe2xx() {
        ExplorationIdempotency record = ExplorationIdempotency.reserve(
                userId, "START_SESSION", 0L, "key-2", new byte[32]);
        record.storeSuccess(409, "{}");

        // 실패 응답은 성공으로 저장하지 않는다(D07 5절).
        assertThatThrownBy(() -> idempotencyRepository.saveAndFlush(record))
                .hasMessageContaining("ck_exploration_idempotency_response");
    }

    // ---- 결과: 회차당 1개 ----

    @Test
    void resultIsUniquePerSession() {
        ExplorationSession session = sessionRepository.saveAndFlush(newSession());
        resultRepository.saveAndFlush(result(session.getId()));

        assertThatThrownBy(() -> resultRepository.saveAndFlush(result(session.getId())))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_exploration_result_session");
        assertThat(resultRepository.findBySessionId(session.getId())).isPresent();
    }

    // ---- helpers ----

    private boolean attemptStart(Runnable beforeCommit) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                sessionRepository.saveAndFlush(newSession());
                beforeCommit.run();
            });
            return true;
        } catch (DataIntegrityViolationException exception) {
            return false;
        }
    }

    private ExplorationSession newSession() {
        return ExplorationSession.start(userId, ExplorationRunMode.DEV, null, "STAGE_1", versions, LocalDateTime.now());
    }

    private ExplorationIssuedQuestion issue(Long sessionId, String questionId, int ordinal) {
        return ExplorationIssuedQuestion.issue(sessionId, questionId, "1.0", null, null, "STAGE_1", ordinal, true,
                "{\"text\":\"테스트 문항\"}", LocalDateTime.now());
    }

    private ExplorationIssuedQuestion issuedForNewSession() {
        ExplorationSession session = sessionRepository.saveAndFlush(newSession());
        return issuedRepository.saveAndFlush(issue(session.getId(), "INT-HUM-01", 1));
    }

    private ExplorationAnswerRevision answer(Long issuedId, int revision, String optionCode, String mutationId) {
        return ExplorationAnswerRevision.record(issuedId, revision, optionCode, mutationId, new byte[32], "{}",
                LocalDateTime.now());
    }

    private ExplorationResult result(Long sessionId) {
        return ExplorationResult.of(sessionId, "RESULT_CONTRACT_V1_0", ExplorationResultStatus.NO_SUPPORTED_CANDIDATE,
                "INFO_LIMIT_REACHED", new byte[32], "{\"groups\":[],\"majors\":[]}");
    }

    private long insertCatalog(String type) {
        String version = "TEST_" + type + "_" + runId;
        jdbcTemplate.update(
                "INSERT INTO exploration_catalog (catalog_type, version, content_status, implementation_review_status, "
                        + "domain_review_status, content_hash) VALUES (?, ?, 'REVIEWED', 'APPROVED_FOR_IMPLEMENTATION', "
                        + "'PENDING', ?)", type, version, "0".repeat(64));
        long id = jdbcTemplate.queryForObject(
                "SELECT id FROM exploration_catalog WHERE catalog_type = ? AND version = ?", Long.class, type, version);
        catalogIds.add(id);
        return id;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
