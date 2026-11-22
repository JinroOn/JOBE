package com.jinroon.jobe.domain.exploration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jinroon.jobe.domain.exploration.catalog.ExplorationCatalogLoader;
import com.jinroon.jobe.domain.exploration.service.ExplorationSessionService;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.domain.user.service.AccountDeletionService;
import com.jinroon.jobe.global.security.JwtProvider;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Real MySQL transactions + JWT. Run only against a disposable, dedicated test database. */
@SpringBootTest(properties = {"exploration.enabled=true", "exploration.runtime.run-mode=DEV",
        "exploration.runtime.dev-user-ids=8200000001,8200000002"})
@ActiveProfiles("exploration-dev")
@AutoConfigureMockMvc
abstract class ExplorationApiTestSupport {
    protected static final String API = "/api/exploration/v1/sessions";
    protected static final String START = "{\"diagnosisType\":\"EXPLORATION_V1\"}";
    protected static final long OWNER = 8200000001L, OTHER = 8200000002L, OUTSIDE = 8200000003L;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired JwtProvider jwt;
    @Autowired ExplorationCatalogLoader loader;
    @Autowired AccountDeletionService deletion;
    @Autowired ExplorationSessionService service;
    @Autowired com.jinroon.jobe.domain.major.repository.MajorRepository fixtureMajors;
    protected String owner, other, outside;
    private final Set<Long> extraFixtureMajorIds = new HashSet<>();

    @BeforeEach void prepare() {
        loader.load(Path.of("datasets/exploration"));
        owner = token(OWNER); other = token(OTHER); outside = token(OUTSIDE);
    }

    @AfterEach void cleanup() {
        for (long id : List.of(OWNER, OTHER, OUTSIDE)) if (users.existsById(id)) deletion.delete(id);
        for (long id : extraFixtureMajorIds) jdbc.update("DELETE FROM majors WHERE id=?", id);
        jdbc.update("DELETE s FROM exploration_runtime_snapshot s LEFT JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash WHERE p.session_id IS NULL");
    }

    protected String token(long fixedId) {
        User user = users.saveAndFlush(User.registerEmailUser(UUID.randomUUID() + "@session.invalid", "test-hash", "synthetic", null));
        jdbc.update("UPDATE users SET id=? WHERE id=?", fixedId, user.getId());
        return "Bearer " + jwt.createToken(users.findById(fixedId).orElseThrow());
    }
    protected JsonNode start(String key) throws Exception { return postJson("", owner, key, START, 201); }
    protected JsonNode postJson(String suffix, String token, String key, String body, int status) throws Exception {
        String path = suffix.isEmpty() ? API : API + "/" + suffix;
        return response(post(path).header("Authorization", token).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body), status);
    }
    protected JsonNode getJson(String suffix, String token, int status) throws Exception { return response(get(API + suffix).header("Authorization", token), status); }
    protected JsonNode response(MockHttpServletRequestBuilder request, int status) throws Exception {
        String body = mvc.perform(request).andExpect(status().is(status)).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        return body.isBlank() ? mapper.nullNode() : mapper.readTree(body);
    }
    protected int count(String table, String where, Object... args) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, args); }
    /** A valid unrelated legacy plan makes whole-table-zero assertions fail even in a disposable DB. */
    protected long unrelatedLegacyPlan() {
        long major = fixtureMajors.saveAndFlush(com.jinroon.jobe.global.common.entity.EntityFormMapper.create(
                com.jinroon.jobe.domain.major.entity.Major.class,
                Map.of("name", "unrelated-"+UUID.randomUUID(), "category", "test", "difficulty", "mid"))).getId();
        extraFixtureMajorIds.add(major);
        long session = insertFixture("INSERT INTO diagnosis_sessions(user_id,status,current_step,started_at,input_snapshot) VALUES (?,'completed',1,NOW(),'{}')", OTHER);
        long result = insertFixture("INSERT INTO diagnosis_results(user_id,diagnosis_session_id,competency_vector,tendency_vector,ai_comment_status) VALUES (?,?,'{}','{}','NOT_REQUESTED')", OTHER, session);
        long score = insertFixture("INSERT INTO result_major_scores(diagnosis_result_id,major_id,is_failed) VALUES (?,?,false)", result, major);
        return insertFixture("INSERT INTO major_weekly_plans(diagnosis_result_id,result_major_score_id,version_no,active_version,fallback,ai_plan_status) VALUES (?,?,1,true,false,'NOT_REQUESTED')", result, score);
    }
    private long insertFixture(String sql, Object... args) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            for (int i=0;i<args.length;i++) statement.setObject(i+1,args[i]);
            return statement;
        },key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }
    /** Restore by shared hash, even if the test deletes its pin or throws an assertion failure. */
    protected AutoCloseable preserveRuntimeSnapshot(long sessionId) {
        var row = jdbc.queryForMap("SELECT s.content_hash,s.payload_json FROM exploration_runtime_snapshot s "
                + "JOIN exploration_session_pin p ON p.snapshot_hash=s.content_hash WHERE p.session_id=?", sessionId);
        String hash = (String) row.get("content_hash"), original = (String) row.get("payload_json");
        return () -> assertThat(jdbc.update("UPDATE exploration_runtime_snapshot SET payload_json=? WHERE content_hash=?", original, hash)).isEqualTo(1);
    }
    protected <T> List<T> parallel(Callable<T> action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        Callable<T> task = () -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout"); return action.call(); };
        try {
            Future<T> first = pool.submit(task), second = pool.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue(); }
    }
}
