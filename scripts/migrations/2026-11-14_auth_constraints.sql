-- ============================================================
-- 2026-11-14 인증·회원 제약 마이그레이션 (MySQL 8.0)
--
-- 대상
--   1) users.email 유일 제약 (uk_users_email)
--   2) user_favorites(user_id, major_id) 유일 제약 (uk_user_favorites_user_major)
--   3) email_verifications / password_reset_tokens 의 failed_attempts 컬럼 (PR #74)
--
-- 왜 필요한가
--   ddl-auto=update는 기존 테이블에 유일 제약을 확실히 추가하지 않고, 중복 데이터가 있으면
--   조용히 실패한다. 운영 DB는 이 파일로 직접 적용한다.
--   docker-compose로 init.sql부터 만든 DB에는 uk_users_email이 이미 있다.
--
-- 실행 순서
--   [1단계] 중복 확인 쿼리만 먼저 실행한다. 결과가 0행이어야 한다.
--   [2단계] 중복이 있으면 어떤 행을 남길지 팀에서 정한 뒤 정리한다. (이 파일은 자동 삭제하지 않음)
--   [3단계] 적용 블록을 실행한다. 이미 적용된 항목은 건너뛰므로 여러 번 실행해도 된다.
--
-- 적용 전 백업
--   mysqldump -u <user> -p jobe users user_favorites email_verifications password_reset_tokens > backup_2026-11-14.sql
-- ============================================================


-- ------------------------------------------------------------
-- [1단계] 중복 확인 (결과가 0행이어야 3단계 진행)
-- ------------------------------------------------------------

-- users.email 중복
SELECT email, COUNT(*) AS cnt, GROUP_CONCAT(id ORDER BY id) AS ids
FROM users
GROUP BY email
HAVING COUNT(*) > 1;

-- user_favorites(user_id, major_id) 중복
SELECT user_id, major_id, COUNT(*) AS cnt, GROUP_CONCAT(id ORDER BY id) AS ids
FROM user_favorites
GROUP BY user_id, major_id
HAVING COUNT(*) > 1;


-- ------------------------------------------------------------
-- [2단계 참고] 즐겨찾기 중복 정리 예시: 가장 먼저 만든 행(id 최소)만 남긴다.
-- 즐겨찾기는 같은 전공 중복이라 어느 행을 남겨도 의미가 같다. 확인 후 주석을 풀어 실행.
-- users 중복은 계정·결과 데이터가 연결돼 있으므로 자동 정리하지 말고 개별 확인한다.
-- ------------------------------------------------------------

-- DELETE f FROM user_favorites f
-- JOIN user_favorites keep
--   ON keep.user_id = f.user_id AND keep.major_id = f.major_id AND keep.id < f.id;


-- ------------------------------------------------------------
-- [3단계] 적용 (이미 있으면 건너뜀)
-- ------------------------------------------------------------

-- 1) uk_users_email
SET @exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'users' AND index_name = 'uk_users_email'
);
SET @sql := IF(@exists = 0,
    'ALTER TABLE users ADD CONSTRAINT uk_users_email UNIQUE (email)',
    'SELECT ''uk_users_email already exists'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 2) uk_user_favorites_user_major
SET @exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'user_favorites' AND index_name = 'uk_user_favorites_user_major'
);
SET @sql := IF(@exists = 0,
    'ALTER TABLE user_favorites ADD CONSTRAINT uk_user_favorites_user_major UNIQUE (user_id, major_id)',
    'SELECT ''uk_user_favorites_user_major already exists'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 3) email_verifications.failed_attempts (nullable, NULL은 0회로 처리)
SET @exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'email_verifications' AND column_name = 'failed_attempts'
);
SET @sql := IF(@exists = 0,
    'ALTER TABLE email_verifications ADD COLUMN failed_attempts INT NULL',
    'SELECT ''email_verifications.failed_attempts already exists'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 4) password_reset_tokens.failed_attempts
SET @exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'password_reset_tokens' AND column_name = 'failed_attempts'
);
SET @sql := IF(@exists = 0,
    'ALTER TABLE password_reset_tokens ADD COLUMN failed_attempts INT NULL',
    'SELECT ''password_reset_tokens.failed_attempts already exists'' AS skipped');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;


-- ------------------------------------------------------------
-- 적용 확인
-- ------------------------------------------------------------
SELECT table_name, index_name, GROUP_CONCAT(column_name ORDER BY seq_in_index) AS columns
FROM information_schema.statistics
WHERE table_schema = DATABASE()
  AND index_name IN ('uk_users_email', 'uk_user_favorites_user_major')
GROUP BY table_name, index_name;
