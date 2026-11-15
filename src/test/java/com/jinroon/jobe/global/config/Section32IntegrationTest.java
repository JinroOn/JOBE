package com.jinroon.jobe.global.config;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jinroon.jobe.domain.exploration.catalog.ExplorationCatalogLoader;
import com.jinroon.jobe.domain.exploration.catalog.ExplorationCatalogLoader.ExpectedCounts;
import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.domain.major.repository.MajorRepository;
import com.jinroon.jobe.domain.major.service.MajorCatalogBindingService;
import com.jinroon.jobe.domain.user.entity.User;
import com.jinroon.jobe.domain.user.entity.UserConsent;
import com.jinroon.jobe.domain.user.enums.UserEnums.*;
import com.jinroon.jobe.domain.user.repository.*;
import com.jinroon.jobe.domain.user.service.*;
import com.jinroon.jobe.global.common.entity.EntityFormMapper;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import com.jinroon.jobe.global.security.JwtProvider;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class Section32IntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired UserConsentRepository consents;
    @Autowired MajorRepository majors;
    @Autowired JwtProvider jwt;
    @Autowired AccountDeletionService deletion;
    @Autowired ExplorationCatalogLoader loader;
    @Autowired MajorCatalogBindingService binding;
    @Autowired UserWriteGuard writeGuard;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired com.jinroon.jobe.domain.major.service.MajorService majorService;

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void concurrentMajorReferencePreventsDeletionAfterReferenceCommits() throws Exception {
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        Long owner=tx.execute(status->user(UserRole.member).getId());
        Long major=tx.execute(status->major("concurrent reference").getId());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1), deleting=new CountDownLatch(1);
        try {
            Future<?> writer=pool.submit(()->tx.executeWithoutResult(status->{
                majors.findByIdForReference(major).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("writer timeout");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                jdbc.update("INSERT INTO user_favorites(user_id,major_id) VALUES (?,?)",owner,major);
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> removing=pool.submit(()->{ deleting.countDown(); majorService.deleteMajor(major); });
            assertThat(deleting.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->removing.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            writer.get(10,TimeUnit.SECONDS);
            assertThatThrownBy(()->removing.get(10,TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).cause().isInstanceOf(CustomException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.MAJOR_IN_USE);
            assertThat(majors.existsById(major)).isTrue();
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
            deletion.delete(owner);
            majorService.deleteMajor(major);
        }
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void withdrawalWaitsForExistingWriterAndRejectsLateWriter() throws Exception {
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        Long owner=tx.execute(status->user(UserRole.member).getId());
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch locked=new CountDownLatch(1), release=new CountDownLatch(1), deleting=new CountDownLatch(1);
        try {
            Future<?> writer=pool.submit(()->tx.executeWithoutResult(status->{
                writeGuard.requireActive(owner);
                locked.countDown();
                try {
                    if (!release.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("writer timeout");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                consents.saveAndFlush(UserConsent.of(owner,true,true,false));
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            Future<?> removing=pool.submit(()->{ deleting.countDown(); deletion.delete(owner); });
            assertThat(deleting.await(10,TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->removing.get(250,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            writer.get(10,TimeUnit.SECONDS);
            removing.get(10,TimeUnit.SECONDS);
            assertThat(users.existsById(owner)).isFalse();
            assertThat(count("user_consents","user_id",owner)).isZero();
            assertThatThrownBy(()->tx.executeWithoutResult(status->writeGuard.requireActive(owner)))
                    .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(15,TimeUnit.SECONDS)).isTrue();
            if (users.existsById(owner)) deletion.delete(owner);
        }
    }

    @Test
    @Transactional(propagation=Propagation.NOT_SUPPORTED)
    void failedDeletionTransactionRestoresAccountAndConsentTogether() {
        TransactionTemplate tx=new TransactionTemplate(transactionManager);
        Long owner=tx.execute(status->{
            User user=user(UserRole.member);
            consents.saveAndFlush(UserConsent.of(user.getId(),true,true,false));
            return user.getId();
        });
        try {
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                deletion.delete(owner);
                throw new IllegalStateException("simulate transaction failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(users.findById(owner)).isPresent();
            assertThat(count("user_consents","user_id",owner)).isOne();
        } finally { deletion.delete(owner); }
    }

    @Test
    void withdrawalDeletesPersonalGraphAndKeepsOtherUserAndSharedMajor() throws Exception {
        User owner=user(UserRole.member), other=user(UserRole.member);
        Major major=major("컴퓨터공학과");
        long result=legacyGraph(owner,major), otherResult=legacyGraph(other,major);
        consents.saveAndFlush(UserConsent.of(owner.getId(),true,true,true));
        jdbc.update("INSERT INTO user_favorites(user_id,major_id) VALUES (?,?)",owner.getId(),major.getId());
        jdbc.update("INSERT INTO sessions(user_id,refresh_token,expires_at) VALUES (?,'private-refresh',DATE_ADD(NOW(),INTERVAL 1 DAY))",owner.getId());
        jdbc.update("INSERT INTO email_verifications(email,token,is_used,expires_at) VALUES (?,'123456',false,DATE_ADD(NOW(),INTERVAL 1 DAY))",owner.getEmail());
        jdbc.update("INSERT INTO password_reset_tokens(email,token,is_used,expires_at) VALUES (?,'654321',false,DATE_ADD(NOW(),INTERVAL 1 DAY))",owner.getEmail());

        mvc.perform(delete("/api/users/me").header("Authorization",token(owner))).andExpect(status().isNoContent());

        for (String table:new String[]{"users","user_consents","user_favorites","sessions","diagnosis_sessions","diagnosis_results","consultation_sessions"}) {
            String column=table.equals("users")?"id":"user_id";
            assertThat(count(table,column,owner.getId())).as(table).isZero();
        }
        assertThat(count("major_weekly_plans","diagnosis_result_id",result)).isZero();
        assertThat(count("result_major_scores","diagnosis_result_id",result)).isZero();
        assertThat(count("email_verifications","email",owner.getEmail())).isZero();
        assertThat(count("password_reset_tokens","email",owner.getEmail())).isZero();
        assertThat(count("diagnosis_results","id",otherResult)).isOne();
        assertThat(users.existsById(other.getId())).isTrue();
        assertThat(majors.existsById(major.getId())).isTrue();
    }

    @Test
    void oldAccessTokenCannotAuthenticateAfterWithdrawalOrEmailReuse() throws Exception {
        User owner=user(UserRole.member);
        String old=token(owner),email=owner.getEmail();
        mvc.perform(delete("/api/users/me").header("Authorization",old)).andExpect(status().isNoContent());
        mvc.perform(get("/api/users/me").header("Authorization",old)).andExpect(status().isUnauthorized());
        User replacement=users.saveAndFlush(User.registerEmailUser(email,"hash","new",null));
        mvc.perform(get("/api/users/me").header("Authorization",old)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/users/me").header("Authorization",token(replacement))).andExpect(status().isOk());
    }

    @Test
    void consentChangesAppendAndMarketingOnlyUpdateKeepsRequiredConsent() throws Exception {
        User owner=user(UserRole.member);
        var original=consents.saveAndFlush(UserConsent.of(owner.getId(),true,true,true));
        mvc.perform(post("/api/users/consents").header("Authorization",token(owner))
                .contentType(MediaType.APPLICATION_JSON).content("{\"marketingAgreed\":false,\"termsVersion\":\"spoof\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.termsVersion").value("1.0"));
        var history=consents.findByUserIdOrderByAgreedAtDescIdDesc(owner.getId());
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getTermsAgreed()).isTrue();
        assertThat(history.get(0).getPrivacyAgreed()).isTrue();
        assertThat(history.get(0).getMarketingAgreed()).isFalse();
        assertThat(consents.findById(original.getId()).orElseThrow().getMarketingAgreed()).isTrue();
        mvc.perform(get("/api/users/me/consents/latest").header("Authorization",token(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.marketingAgreed").value(false));
    }

    @Test
    void requiredConsentWithdrawalUsesExplicitAccountDeletionRoute() throws Exception {
        User owner=user(UserRole.member);
        consents.saveAndFlush(UserConsent.of(owner.getId(),true,true,false));
        mvc.perform(post("/api/users/consents").header("Authorization",token(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"privacyAgreed\":false}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ACCOUNT_WITHDRAWAL_REQUIRED"));
        assertThat(consents.findByUserId(owner.getId())).hasSize(1);
    }

    @Test
    void majorPublicResponseHasNoLegacyScoringFields() throws Exception {
        Major major=major("public");
        mvc.perform(get("/api/majors/"+major.getId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("public"))
                .andExpect(jsonPath("$.reqMathLogic").doesNotExist())
                .andExpect(jsonPath("$.tendLogicalInquiry").doesNotExist())
                .andExpect(jsonPath("$.thrMathLogic").doesNotExist());
    }

    @Test
    void administratorCanOnlyPatchMajorDescriptionAndCareer() throws Exception {
        User admin=user(UserRole.admin);
        Major major=major("immutable-name");
        mvc.perform(patch("/api/admin/majors/"+major.getId()).header("Authorization",token(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"description\":\"updated\",\"careerPaths\":\"jobs\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").value("updated"));
        for (String field:new String[]{"name","reqMathLogic","majorCode","contentStatus"}) {
            mvc.perform(patch("/api/admin/majors/"+major.getId()).header("Authorization",token(admin))
                    .contentType(MediaType.APPLICATION_JSON).content("{\""+field+"\":\"forbidden\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(majors.findById(major.getId()).orElseThrow().getName()).isEqualTo("immutable-name");
    }

    @Test
    void memberCannotAccessAdministratorMajorApi() throws Exception {
        User member=user(UserRole.member);
        mvc.perform(get("/api/admin/majors").header("Authorization",token(member))).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/majors").header("Authorization",token(member)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"x\",\"category\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void catalogBindingChecksNameCodeSourceAndRejectsChangesToActiveCatalog() {
        loader.load(Path.of("datasets/exploration"));
        Major major=major("문예창작학과");
        String version="MAJOR_MAPPING_V1_2_IMPLEMENTATION_CANDIDATE";
        assertThat(binding.bind(version,"MAJOR_ROW_6",major.getId()).majorId()).isEqualTo(major.getId());
        assertThat(binding.bind(version,"MAJOR_ROW_6",major.getId()).majorId()).isEqualTo(major.getId());
        assertThatThrownBy(()->binding.bind(version,"MAJOR_ROW_93",major.getId()))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.MAJOR_BINDING_MISMATCH);
        jdbc.update("UPDATE exploration_catalog SET content_status='ACTIVE' WHERE catalog_type='MAJOR_MAPPING' AND version=?",version);
        assertThatThrownBy(()->binding.bind(version,"MAJOR_ROW_6",major.getId()))
                .isInstanceOf(CustomException.class).extracting("errorCode").isEqualTo(ErrorCode.CATALOG_IMMUTABLE);
    }

    @Test
    void referencedMajorCannotBeDeleted() throws Exception {
        User owner=user(UserRole.member),admin=user(UserRole.admin);
        Major major=major("referenced");
        jdbc.update("INSERT INTO user_favorites(user_id,major_id) VALUES (?,?)",owner.getId(),major.getId());
        mvc.perform(delete("/api/admin/majors/"+major.getId()).header("Authorization",token(admin)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MAJOR_IN_USE"));
        assertThat(majors.existsById(major.getId())).isTrue();
    }

    @Test
    void disabledExplorationIsNotPubliclyAccessible() throws Exception {
        mvc.perform(get("/api/exploration/v1/sessions")).andExpect(status().isNotFound());
    }

    @Test
    void deletionRemovesExplorationRevisionsResultsAndIdempotencyButNotCatalogs() {
        User owner=user(UserRole.member);
        loader.load(Path.of("src/test/resources/exploration-fixture"),new ExpectedCounts(6,17,1,2));
        long q=catalog("QUESTION_CATALOG"),o=catalog("OPTION_CATALOG"),v=catalog("OPTION_VARIANT"),m=catalog("MAJOR_MAPPING"),s=catalog("SEMANTIC_CONTRACT");
        long r=jdbc.queryForObject("SELECT id FROM exploration_ruleset WHERE version='FIXTURE_RULESET_V1_0'",Long.class);
        long session=insert("INSERT INTO exploration_session(user_id,diagnosis_type,run_mode,status,stage,question_catalog_id,option_catalog_id,option_variant_catalog_id,major_mapping_catalog_id,ruleset_id,semantic_contract_catalog_id,started_at) "
                +"VALUES (?,'EXPLORATION_V1','DEV','in_progress','BROAD',?,?,?,?,?,?,NOW())",owner.getId(),q,o,v,m,r,s);
        long issued=insert("INSERT INTO exploration_issued_question(session_id,question_id,question_version,stage,ordinal,snapshot_json,shown_at) VALUES (?,'Q','1','BROAD',1,'{}',NOW())",session);
        jdbc.update("INSERT INTO exploration_answer_revision(issued_id,answer_revision,option_code,is_current,client_mutation_id,mutation_request_hash,mutation_response_json,answered_at) VALUES (?,1,'A',true,'m',UNHEX(REPEAT('00',32)),'{}',NOW())",issued);
        jdbc.update("INSERT INTO exploration_idempotency(user_id,operation,resource_id,idempotency_key,request_hash) VALUES (?,'START',0,'key',UNHEX(REPEAT('00',32)))",owner.getId());
        jdbc.update("INSERT INTO exploration_result(session_id,result_contract_version,result_status,completion_reason,input_hash,result_json) VALUES (?,'RESULT_V1_0','READY','NORMAL',UNHEX(REPEAT('00',32)),'{}')",session);
        deletion.delete(owner.getId());
        assertThat(count("exploration_answer_revision","issued_id",issued)).isZero();
        assertThat(count("exploration_result","session_id",session)).isZero();
        assertThat(count("exploration_session","id",session)).isZero();
        assertThat(count("exploration_idempotency","user_id",owner.getId())).isZero();
        assertThat(count("exploration_catalog","id",q)).isOne();
    }

    private User user(UserRole role) {
        return users.saveAndFlush(User.createByAdmin(UUID.randomUUID()+"@test.local","hash","member",null,role,LoginType.email,UserStatus.active));
    }
    private Major major(String name) {
        return majors.saveAndFlush(EntityFormMapper.create(Major.class,Map.of("name",name,"category","test","difficulty","mid")));
    }
    private String token(User user) { return "Bearer "+jwt.createToken(user); }
    private long insert(String sql,Object...args) {
        jdbc.update(sql,args); return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    private long count(String table,String column,Object value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE "+column+"=?",Long.class,value);
    }
    private long catalog(String type) {
        return jdbc.queryForObject("SELECT id FROM exploration_catalog WHERE catalog_type=? AND version LIKE 'FIXTURE%'",Long.class,type);
    }
    private long legacyGraph(User user,Major major) {
        long session=insert("INSERT INTO diagnosis_sessions(user_id,status,current_step,started_at,input_snapshot) VALUES (?,'completed',1,NOW(),'{}')",user.getId());
        long result=insert("INSERT INTO diagnosis_results(user_id,diagnosis_session_id,competency_vector,tendency_vector,ai_comment_status) VALUES (?,?,'{}','{}','NOT_REQUESTED')",user.getId(),session);
        long score=insert("INSERT INTO result_major_scores(diagnosis_result_id,major_id,is_failed) VALUES (?,?,false)",result,major.getId());
        long plan=insert("INSERT INTO major_weekly_plans(diagnosis_result_id,result_major_score_id,version_no,active_version,fallback,ai_plan_status) VALUES (?,?,1,true,false,'NOT_REQUESTED')",result,score);
        jdbc.update("INSERT INTO major_weekly_plan_items(weekly_plan_id,week_no,goal,is_completed) VALUES (?,1,'private',false)",plan);
        jdbc.update("INSERT INTO major_weekly_plan_risk_notes(weekly_plan_id,note) VALUES (?,'private')",plan);
        long conversation=insert("INSERT INTO consultation_sessions(user_id,diagnosis_result_id,started_at,title) VALUES (?,?,NOW(),'private')",user.getId(),result);
        jdbc.update("INSERT INTO consultation_logs(consultation_session_id,role,content) VALUES (?,'user','private')",conversation);
        return result;
    }
}
