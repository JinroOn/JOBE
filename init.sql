-- JOBE MySQL 초기화 스크립트
-- docker-compose mysql 볼륨이 없을 때 최초 1회 자동 실행
-- Spring Boot ddl-auto=update가 이후 스키마를 보완함

USE jobe;

-- ============================================================
-- users
-- ============================================================
CREATE TABLE IF NOT EXISTS users (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255),
    nickname       VARCHAR(50)  NOT NULL,
    profile_image_url VARCHAR(500),
    role           VARCHAR(20)  NOT NULL,
    login_type     VARCHAR(20)  NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    email_verified_at DATETIME(6),
    last_login_at  DATETIME(6),
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO users
    (email, password_hash, nickname, profile_image_url, role, login_type, status, email_verified_at, created_at, updated_at)
VALUES
    ('admin@jinroon.com',  'pbkdf2$120000$c2FsdA==$dGVzdA==', '관리자',    NULL, 'admin',  'email', 'active', NOW(6), NOW(6), NOW(6)),
    ('user1@jinroon.com',  'pbkdf2$120000$c2FsdA==$dGVzdA==', '테스트유저1', NULL, 'member', 'email', 'active', NOW(6), NOW(6), NOW(6)),
    ('user2@jinroon.com',  'pbkdf2$120000$c2FsdA==$dGVzdA==', '테스트유저2', NULL, 'member', 'email', 'active', NOW(6), NOW(6), NOW(6));

-- ============================================================
-- majors
-- ============================================================
CREATE TABLE IF NOT EXISTS majors (
    id                      BIGINT      NOT NULL AUTO_INCREMENT,
    name                    VARCHAR(100) NOT NULL,
    category                VARCHAR(50)  NOT NULL,
    difficulty              VARCHAR(20)  NOT NULL,
    description             TEXT,
    career_paths            TEXT,
    req_math_logic          FLOAT        NOT NULL DEFAULT 0,
    req_problem_solving     FLOAT        NOT NULL DEFAULT 0,
    req_info_tech           FLOAT        NOT NULL DEFAULT 0,
    req_implementation      FLOAT        NOT NULL DEFAULT 0,
    req_system_understanding FLOAT       NOT NULL DEFAULT 0,
    req_data_analysis       FLOAT        NOT NULL DEFAULT 0,
    req_communication       FLOAT        NOT NULL DEFAULT 0,
    req_collaboration       FLOAT        NOT NULL DEFAULT 0,
    req_self_management     FLOAT        NOT NULL DEFAULT 0,
    tend_logical_inquiry    FLOAT        NOT NULL DEFAULT 0,
    tend_practical_tech     FLOAT        NOT NULL DEFAULT 0,
    tend_art_creative       FLOAT        NOT NULL DEFAULT 0,
    tend_social_cooperation FLOAT        NOT NULL DEFAULT 0,
    tend_life_health        FLOAT        NOT NULL DEFAULT 0,
    tend_education_guide    FLOAT        NOT NULL DEFAULT 0,
    tend_theory_academic    FLOAT        NOT NULL DEFAULT 0,
    tend_data_analytics     FLOAT        NOT NULL DEFAULT 0,
    tend_system_operation   FLOAT        NOT NULL DEFAULT 0,
    thr_math_logic          FLOAT                 DEFAULT 0,
    thr_info_tech           FLOAT                 DEFAULT 0,
    created_at              DATETIME(6),
    updated_at              DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_majors_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO majors
    (name, category, difficulty, description, career_paths,
     req_math_logic, req_problem_solving, req_info_tech, req_implementation, req_system_understanding,
     req_data_analysis, req_communication, req_collaboration, req_self_management,
     tend_logical_inquiry, tend_practical_tech, tend_art_creative, tend_social_cooperation, tend_life_health,
     tend_education_guide, tend_theory_academic, tend_data_analytics, tend_system_operation,
     thr_math_logic, thr_info_tech, created_at, updated_at)
VALUES
    -- 1. 컴퓨터공학과
    ('컴퓨터공학과', '공학계열', 'high',
     '소프트웨어와 하드웨어를 설계하고 구현하는 전공',
     '백엔드개발자,프론트엔드개발자,AI엔지니어,시스템엔지니어',
     90, 90, 95, 95, 85, 75, 65, 70, 75,
     90, 95, 30, 50, 20, 30, 70, 75, 85,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 2. 데이터사이언스학과
    ('데이터사이언스학과', '융합계열', 'high',
     '데이터 분석과 머신러닝으로 인사이트를 도출하는 전공',
     '데이터사이언티스트,ML엔지니어,데이터분석가,AI연구원',
     95, 85, 85, 75, 70, 95, 70, 70, 80,
     95, 75, 25, 50, 20, 35, 85, 95, 65,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 3. 전자공학과
    ('전자공학과', '공학계열', 'high',
     '전자 회로와 시스템을 설계하는 전공',
     '반도체엔지니어,임베디드개발자,통신엔지니어',
     90, 85, 80, 85, 90, 70, 65, 70, 75,
     90, 90, 25, 45, 20, 25, 80, 65, 90,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 4. 기계공학과
    ('기계공학과', '공학계열', 'high',
     '기계 시스템을 설계하고 제조하는 전공',
     '기계설계엔지니어,자동화엔지니어,로보틱스엔지니어',
     85, 85, 70, 80, 85, 65, 65, 70, 75,
     85, 90, 40, 50, 30, 25, 75, 60, 85,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 5. 인공지능학과
    ('인공지능학과', '공학계열', 'high',
     'AI 알고리즘과 딥러닝을 연구하고 개발하는 전공',
     'AI연구원,ML엔지니어,자연어처리전문가,비전AI엔지니어',
     95, 90, 90, 85, 80, 90, 65, 70, 80,
     95, 85, 30, 50, 20, 35, 90, 90, 70,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 6. 경영학과
    ('경영학과', '인문사회계열', 'mid',
     '기업 경영과 비즈니스 전략을 배우는 전공',
     '경영컨설턴트,마케터,인사담당자,창업가',
     65, 75, 60, 50, 65, 70, 90, 90, 85,
     65, 55, 55, 90, 40, 60, 65, 70, 55,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 7. 심리학과
    ('심리학과', '인문사회계열', 'mid',
     '인간의 마음과 행동을 과학적으로 탐구하는 전공',
     '임상심리사,상담사,UX연구원,HR전문가',
     60, 70, 55, 45, 55, 65, 85, 80, 80,
     70, 45, 60, 90, 80, 75, 75, 65, 40,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 8. 생명공학과
    ('생명공학과', '자연과학계열', 'high',
     '생명 현상을 공학적으로 응용하는 전공',
     '바이오연구원,제약개발자,유전공학자,의공학엔지니어',
     80, 80, 70, 65, 75, 80, 65, 75, 80,
     85, 70, 30, 60, 90, 50, 85, 80, 60,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 9. 통계학과
    ('통계학과', '자연과학계열', 'high',
     '데이터를 수집하고 분석하는 수리 통계 전공',
     '통계분석가,계리사,데이터분석가,리서처',
     95, 80, 75, 65, 65, 95, 70, 65, 80,
     95, 65, 25, 55, 25, 45, 90, 95, 55,
     0.0, 0.0, NOW(6), NOW(6)),

    -- 10. 산업공학과
    ('산업공학과', '공학계열', 'mid',
     '시스템 효율화와 최적화를 연구하는 전공',
     '경영컨설턴트,SCM전문가,데이터분석가,프로세스엔지니어',
     80, 85, 75, 65, 80, 80, 75, 80, 80,
     80, 75, 40, 80, 35, 50, 75, 80, 75,
     0.0, 0.0, NOW(6), NOW(6));

-- ============================================================
-- notices
-- ============================================================
CREATE TABLE IF NOT EXISTS notices (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    created_by   BIGINT       NOT NULL,
    title        VARCHAR(200) NOT NULL,
    content      TEXT         NOT NULL,
    display_type VARCHAR(20)  NOT NULL,
    start_at     DATETIME(6)  NOT NULL,
    end_at       DATETIME(6)  NOT NULL,
    created_at   DATETIME(6),
    updated_at   DATETIME(6),
    PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO notices
    (created_by, title, content, display_type, start_at, end_at, created_at, updated_at)
VALUES
    (1,
     '진로온 서비스 오픈 안내',
     'AI 기반 전공 적성 진단 서비스 진로온이 정식 오픈했습니다. 역량 진단을 통해 나에게 맞는 전공을 추천받고, 맞춤형 학습 로드맵을 확인해 보세요.',
     'banner',
     NOW(6),
     DATE_ADD(NOW(6), INTERVAL 30 DAY),
     NOW(6), NOW(6)),

    (1,
     '전공 적성 진단 기능 안내',
     '전공 적성 진단은 수리논리, 문제해결, 정보기술 등 9가지 역량을 측정하여 최적의 전공을 추천드립니다. 진단 시작 전 충분한 시간을 확보해 주세요.',
     'popup',
     NOW(6),
     DATE_ADD(NOW(6), INTERVAL 30 DAY),
     NOW(6), NOW(6));
