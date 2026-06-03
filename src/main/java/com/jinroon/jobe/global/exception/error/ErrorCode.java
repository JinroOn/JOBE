package com.jinroon.jobe.global.exception.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 서버 공통
    INTERNAL_SERVER_ERROR(500, "내부 서버 오류입니다."),
    INVALID_INPUT(400, "잘못된 입력값입니다."),
    UNAUTHORIZED(401, "인증이 필요합니다."),
    FORBIDDEN(403, "접근 권한이 없습니다."),
    NOT_FOUND(404, "리소스를 찾을 수 없습니다."),

    // User
    USER_NOT_FOUND(404, "존재하지 않는 사용자입니다."),
    USER_EMAIL_DUPLICATE(409, "이미 사용 중인 이메일입니다."),
    USER_PASSWORD_INVALID(401, "비밀번호가 올바르지 않습니다."),

    // Auth
    AUTH_TOKEN_INVALID(401, "유효하지 않은 토큰입니다."),
    AUTH_TOKEN_EXPIRED(401, "만료된 토큰입니다."),
    AUTH_EMAIL_VERIFICATION_NOT_FOUND(404, "이메일 인증 정보를 찾을 수 없습니다."),
    AUTH_EMAIL_VERIFICATION_EXPIRED(400, "만료된 이메일 인증입니다."),
    AUTH_EMAIL_VERIFICATION_ALREADY_USED(400, "이미 사용된 이메일 인증입니다."),
    AUTH_SESSION_NOT_FOUND(404, "세션을 찾을 수 없습니다."),
    AUTH_SESSION_EXPIRED(401, "만료된 세션입니다."),

    // Consultation
    CONSULTATION_SESSION_NOT_FOUND(404, "상담 세션을 찾을 수 없습니다."),
    CONSULTATION_LOG_NOT_FOUND(404, "상담 로그를 찾을 수 없습니다."),
    CONSULTATION_SESSION_ALREADY_ENDED(409, "이미 종료된 상담 세션입니다."),

    // Diagnosis
    DIAGNOSIS_SESSION_NOT_FOUND(404, "진단 세션을 찾을 수 없습니다."),
    DIAGNOSIS_QUESTION_NOT_FOUND(404, "진단 문항을 찾을 수 없습니다."),
    DIAGNOSIS_ANSWER_NOT_FOUND(404, "진단 답변을 찾을 수 없습니다."),
    DIAGNOSIS_COMPETENCY_RESULT_NOT_FOUND(404, "역량 평가 결과를 찾을 수 없습니다."),
    DIAGNOSIS_TENDENCY_RESULT_NOT_FOUND(404, "성향 평가 결과를 찾을 수 없습니다."),

    // Major
    MAJOR_NOT_FOUND(404, "존재하지 않는 전공입니다."),
    MAJOR_FAVORITE_DUPLICATE(409, "이미 즐겨찾기에 추가된 전공입니다."),
    MAJOR_FAVORITE_NOT_FOUND(404, "즐겨찾기를 찾을 수 없습니다."),

    // Notice
    NOTICE_NOT_FOUND(404, "존재하지 않는 공지사항입니다."),
    NOTICE_INVALID_PERIOD(400, "공지사항 종료일은 시작일 이후여야 합니다."),

    // Plan
    PLAN_NOT_FOUND(404, "존재하지 않는 학습 계획입니다."),
    PLAN_ITEM_NOT_FOUND(404, "존재하지 않는 학습 계획 항목입니다."),
    PLAN_RISK_NOTE_NOT_FOUND(404, "존재하지 않는 리스크 노트입니다."),

    // AI
    AI_GENERATION_IN_PROGRESS(409, "AI generation is already in progress."),
    AI_PLAN_ALREADY_EXISTS(409, "An active AI plan already exists."),

    // Result
    RESULT_NOT_FOUND(404, "존재하지 않는 진단 결과입니다."),
    RESULT_MAJOR_SCORE_NOT_FOUND(404, "전공별 점수를 찾을 수 없습니다."),
    RESULT_SHARE_TOKEN_NOT_FOUND(404, "유효하지 않은 공유 토큰입니다.");

    private final int status;
    private final String message;
}
