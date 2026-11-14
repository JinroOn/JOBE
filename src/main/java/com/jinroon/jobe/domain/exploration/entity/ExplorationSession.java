package com.jinroon.jobe.domain.exploration.entity;

import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationRunMode;
import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 탐색형 진단 회차. 스키마는 Flyway V2가 관리한다.
 * 생성 열 active_user_id(진행 중 사용자당 1개 유일키)는 DB가 계산하므로 매핑하지 않는다.
 * 상태 변경은 세션 행을 FOR UPDATE로 잠근 트랜잭션 안에서만 한다(D07 4절).
 */
@Getter
@Entity
@Table(name = "exploration_session")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExplorationSession {

    public static final String DIAGNOSIS_TYPE = "EXPLORATION_V1";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "diagnosis_type", nullable = false, length = 40)
    private String diagnosisType;

    @Enumerated(EnumType.STRING)
    @Column(name = "run_mode", nullable = false, length = 20)
    private ExplorationRunMode runMode;

    @Column(name = "pilot_approval_reference", length = 200)
    private String pilotApprovalReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExplorationSessionStatus status;

    @Column(nullable = false, length = 40)
    private String stage;

    @Column(nullable = false)
    private Long revision;

    @Column(name = "question_catalog_id", nullable = false)
    private Long questionCatalogId;

    @Column(name = "option_catalog_id", nullable = false)
    private Long optionCatalogId;

    @Column(name = "option_variant_catalog_id", nullable = false)
    private Long optionVariantCatalogId;

    @Column(name = "major_mapping_catalog_id", nullable = false)
    private Long majorMappingCatalogId;

    @Column(name = "ruleset_id", nullable = false)
    private Long rulesetId;

    @Column(name = "semantic_contract_catalog_id", nullable = false)
    private Long semanticContractCatalogId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "abandoned_at")
    private LocalDateTime abandonedAt;

    // DB가 ON UPDATE로 관리
    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;

    /** 시작 시점의 버전 묶음(카탈로그·규칙·의미 계약 ID). */
    public record VersionBundle(
            Long questionCatalogId,
            Long optionCatalogId,
            Long optionVariantCatalogId,
            Long majorMappingCatalogId,
            Long rulesetId,
            Long semanticContractCatalogId
    ) {
    }

    public static ExplorationSession start(Long userId, ExplorationRunMode runMode, String pilotApprovalReference,
                                           String firstStage, VersionBundle versions, LocalDateTime now) {
        ExplorationSession session = new ExplorationSession();
        session.userId = userId;
        session.diagnosisType = DIAGNOSIS_TYPE;
        session.runMode = runMode;
        session.pilotApprovalReference = pilotApprovalReference;
        session.status = ExplorationSessionStatus.in_progress;
        session.stage = firstStage;
        session.revision = 0L;
        session.questionCatalogId = versions.questionCatalogId();
        session.optionCatalogId = versions.optionCatalogId();
        session.optionVariantCatalogId = versions.optionVariantCatalogId();
        session.majorMappingCatalogId = versions.majorMappingCatalogId();
        session.rulesetId = versions.rulesetId();
        session.semanticContractCatalogId = versions.semanticContractCatalogId();
        session.startedAt = now;
        return session;
    }

    public boolean isOwnedBy(Long userId) {
        return this.userId.equals(userId);
    }

    public boolean isInProgress() {
        return status == ExplorationSessionStatus.in_progress;
    }

    public boolean hasRevision(long expectedRevision) {
        return revision == expectedRevision;
    }

    /** 출제·답변·단계 이동 등 변경 작업마다 호출한다. */
    public void increaseRevision() {
        revision = revision + 1;
    }

    public void moveToStage(String nextStage) {
        this.stage = nextStage;
        increaseRevision();
    }

    public void abandon(LocalDateTime now) {
        this.status = ExplorationSessionStatus.abandoned;
        this.abandonedAt = now;
        increaseRevision();
    }

    public void complete(LocalDateTime now) {
        this.status = ExplorationSessionStatus.completed;
        this.completedAt = now;
        increaseRevision();
    }
}
