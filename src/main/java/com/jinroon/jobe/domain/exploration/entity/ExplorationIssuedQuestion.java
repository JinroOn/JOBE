package com.jinroon.jobe.domain.exploration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회차에 실제로 노출한 문항. 노출 당시 문구·선택지를 snapshot_json에 보존하고,
 * 답변은 이 snapshot에 있는 선택지만 허용한다(11번 명세 4절).
 * effective는 현재 분기에서 사용하는지 여부이며, 분기에서 빠져도 행과 답변 이력은 지우지 않는다(D07 2절).
 */
@Getter
@Entity
@Table(name = "exploration_issued_question")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExplorationIssuedQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "question_id", nullable = false, length = 100)
    private String questionId;

    @Column(name = "question_version", nullable = false, length = 20)
    private String questionVersion;

    @Column(name = "comparison_set", length = 100)
    private String comparisonSet;

    @Column(name = "variant_version", length = 100)
    private String variantVersion;

    @Column(nullable = false, length = 40)
    private String stage;

    @Column(nullable = false)
    private Integer ordinal;

    @Column(nullable = false)
    private Boolean effective;

    @Column(nullable = false)
    private Boolean required;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "json")
    private String snapshotJson;

    @Column(name = "shown_at", nullable = false)
    private LocalDateTime shownAt;

    public static ExplorationIssuedQuestion issue(Long sessionId, String questionId, String questionVersion,
                                                  String comparisonSet, String variantVersion, String stage,
                                                  int ordinal, boolean required, String snapshotJson,
                                                  LocalDateTime now) {
        ExplorationIssuedQuestion issued = new ExplorationIssuedQuestion();
        issued.sessionId = sessionId;
        issued.questionId = questionId;
        issued.questionVersion = questionVersion;
        issued.comparisonSet = comparisonSet;
        issued.variantVersion = variantVersion;
        issued.stage = stage;
        issued.ordinal = ordinal;
        issued.effective = true;
        issued.required = required;
        issued.snapshotJson = snapshotJson;
        issued.shownAt = now;
        return issued;
    }

    /** 분기 재평가로 현재 경로에서 빠질 때. 답변은 그대로 보존한다. */
    public void exclude() {
        this.effective = false;
    }

    /** 같은 발급 인스턴스가 다시 유효해질 때. 최초 ordinal·snapshot은 유지한다. */
    public void restore() {
        this.effective = true;
    }
}
