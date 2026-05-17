package com.jinroon.jobe.consultation.domain;

import com.jinroon.jobe.common.domain.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "consultation_logs")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ConsultationLog extends BaseEntitySupport {

    @Column(name = "consultation_session_id", nullable = false)
    private Long consultationSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsultationRole role;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
