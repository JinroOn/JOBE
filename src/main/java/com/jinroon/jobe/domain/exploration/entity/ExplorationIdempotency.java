package com.jinroon.jobe.domain.exploration.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Arrays;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 멱등 요청 기록(D07 5절). 예약 row는 원래 작업과 같은 트랜잭션에서 insert하고,
 * 작업이 성공하면 같은 트랜잭션에서 성공 응답을 저장한다. 실패하면 예약도 롤백된다.
 */
@Getter
@Entity
@Table(name = "exploration_idempotency")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ExplorationIdempotency {

    /** 회차 시작처럼 대상 자원이 아직 없는 작업의 resource_id. */
    public static final long NO_RESOURCE = 0L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, length = 40)
    private String operation;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, columnDefinition = "binary(32)")
    private byte[] requestHash;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_json", columnDefinition = "json")
    private String responseJson;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    public static ExplorationIdempotency reserve(Long userId, String operation, long resourceId,
                                                 String idempotencyKey, byte[] requestHash) {
        ExplorationIdempotency record = new ExplorationIdempotency();
        record.userId = userId;
        record.operation = operation;
        record.resourceId = resourceId;
        record.idempotencyKey = idempotencyKey;
        record.requestHash = requestHash.clone();
        return record;
    }

    public void storeSuccess(int status, String responseJson) {
        this.responseStatus = status;
        this.responseJson = responseJson;
    }

    public boolean hasSameRequest(byte[] otherHash) {
        return Arrays.equals(requestHash, otherHash);
    }

    public byte[] getRequestHash() {
        return requestHash.clone();
    }
}
