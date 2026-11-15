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
 * 답변 이력. 답변 수정은 행 갱신이 아니라 새 revision을 추가하고 이전 행의 isCurrent를 false로 바꾼다.
 * 생성 열 current_issued_id(발급 문항당 최신 답변 최대 1개 유일키)는 DB가 계산하므로 매핑하지 않는다.
 *
 * 주의: 이전 최신 답변 해제(release) → flush → 새 revision insert 순서를 지켜야 한다.
 * flush 없이 insert가 먼저 나가면 유일키 위반이 난다.
 */
@Getter
@Entity
@Table(name = "exploration_answer_revision")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExplorationAnswerRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "issued_id", nullable = false)
    private Long issuedId;

    @Column(name = "answer_revision", nullable = false)
    private Integer answerRevision;

    @Column(name = "option_code", nullable = false, length = 100)
    private String optionCode;

    @Column(name = "is_current", nullable = false)
    private Boolean current;

    @Column(name = "client_mutation_id", nullable = false, length = 128)
    private String clientMutationId;

    @Column(name = "mutation_request_hash", nullable = false, columnDefinition = "binary(32)")
    private byte[] mutationRequestHash;

    @Column(name = "mutation_response_json", nullable = false, columnDefinition = "json")
    private String mutationResponseJson;

    @Column(name = "answered_at", nullable = false)
    private LocalDateTime answeredAt;

    public static ExplorationAnswerRevision record(Long issuedId, int answerRevision, String optionCode,
                                                   String clientMutationId, byte[] mutationRequestHash,
                                                   String mutationResponseJson, LocalDateTime now) {
        ExplorationAnswerRevision revision = new ExplorationAnswerRevision();
        revision.issuedId = issuedId;
        revision.answerRevision = answerRevision;
        revision.optionCode = optionCode;
        revision.current = true;
        revision.clientMutationId = clientMutationId;
        revision.mutationRequestHash = mutationRequestHash.clone();
        revision.mutationResponseJson = mutationResponseJson;
        revision.answeredAt = now;
        return revision;
    }

    /** 새 revision이 이 답변을 대체할 때만 호출한다. 분기 제외로는 호출하지 않는다(D07 2절). */
    public void release() {
        this.current = false;
    }

    /** Finalize inside the same transaction as insertion; never commit an unfinished mutation. */
    public void storeMutationResponse(String responseJson) {
        this.mutationResponseJson = responseJson;
    }

    public byte[] getMutationRequestHash() {
        return mutationRequestHash.clone();
    }
}
