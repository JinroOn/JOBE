-- Personal share state and immutable link generations. No token plaintext or public auto-activation.
CREATE TABLE exploration_share_state (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 result_id BIGINT NOT NULL UNIQUE,
 revision BIGINT NOT NULL DEFAULT 0,
 CONSTRAINT fk_share_state_result FOREIGN KEY(result_id) REFERENCES exploration_result(id) ON DELETE CASCADE,
 CONSTRAINT ck_share_state_revision CHECK(revision>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE exploration_share_link (
 id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
 state_id BIGINT NOT NULL,
 issued_revision BIGINT NOT NULL,
 token_nonce BINARY(32) NOT NULL,
 token_hash BINARY(32) NOT NULL UNIQUE,
 summary_json JSON NOT NULL,
 summary_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 result_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 created_at DATETIME(6) NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 -- VIRTUAL permits CASCADE on the base FK in MySQL.
 active_state_id BIGINT GENERATED ALWAYS AS (CASE WHEN revoked_at IS NULL THEN state_id ELSE NULL END) VIRTUAL,
 CONSTRAINT fk_share_link_state FOREIGN KEY(state_id) REFERENCES exploration_share_state(id) ON DELETE CASCADE,
 CONSTRAINT uq_share_link_revision UNIQUE(state_id,issued_revision),
 CONSTRAINT uq_share_link_active UNIQUE(active_state_id),
 CONSTRAINT ck_share_link_revision CHECK(issued_revision>0),
 CONSTRAINT ck_share_link_expiry CHECK(expires_at>created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
