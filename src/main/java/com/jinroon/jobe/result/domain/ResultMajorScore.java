package com.jinroon.jobe.result.domain;

import com.jinroon.jobe.common.domain.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "result_major_scores")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ResultMajorScore extends BaseEntitySupport {

    @Column(name = "diagnosis_result_id", nullable = false)
    private Long diagnosisResultId;

    @Column(name = "major_id", nullable = false)
    private Long majorId;

    @Column(name = "tendency_score")
    private Float tendencyScore;

    @Column(name = "competency_score")
    private Float competencyScore;

    @Column(name = "final_score")
    private Float finalScore;

    @Column(name = "`rank`")
    private Integer rank;

    @Column(name = "is_failed", nullable = false)
    private Boolean failed;

}
