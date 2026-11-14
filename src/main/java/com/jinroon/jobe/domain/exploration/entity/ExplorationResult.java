package com.jinroon.jobe.domain.exploration.entity;

import com.jinroon.jobe.domain.exploration.enums.ExplorationEnums.ExplorationResultStatus;
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
 * 완료 결과 스냅샷. 회차당 1개(UNIQUE session_id)이고 저장 후 바꾸지 않는다(D06).
 * 그래서 수정 메서드를 두지 않는다.
 */
@Getter
@Entity
@Table(name = "exploration_result")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExplorationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private Long sessionId;

    @Column(name = "result_contract_version", nullable = false, updatable = false, length = 100)
    private String resultContractVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "result_status", nullable = false, updatable = false, length = 40)
    private ExplorationResultStatus resultStatus;

    @Column(name = "completion_reason", nullable = false, updatable = false, length = 40)
    private String completionReason;

    @Column(name = "input_hash", nullable = false, updatable = false, columnDefinition = "binary(32)")
    private byte[] inputHash;

    @Column(name = "result_json", nullable = false, updatable = false, columnDefinition = "json")
    private String resultJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ExplorationResult of(Long sessionId, String resultContractVersion,
                                       ExplorationResultStatus resultStatus, String completionReason,
                                       byte[] inputHash, String resultJson) {
        ExplorationResult result = new ExplorationResult();
        result.sessionId = sessionId;
        result.resultContractVersion = resultContractVersion;
        result.resultStatus = resultStatus;
        result.completionReason = completionReason;
        result.inputHash = inputHash.clone();
        result.resultJson = resultJson;
        return result;
    }

    public byte[] getInputHash() {
        return inputHash.clone();
    }
}
