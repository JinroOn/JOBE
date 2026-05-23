package com.jinroon.jobe.domain.result.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "diagnosis_results")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DiagnosisResult extends BaseEntitySupport {

    @Column(name = "diagnosis_session_id", nullable = false)
    private Long diagnosisSessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "competency_vector", nullable = false, columnDefinition = "JSON")
    private String competencyVector;

    @Column(name = "tendency_vector", nullable = false, columnDefinition = "JSON")
    private String tendencyVector;

    @Column(name = "top_majors_json", columnDefinition = "JSON")
    private String topMajorsJson;

    @Column(name = "share_token", length = 128)
    private String shareToken;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
