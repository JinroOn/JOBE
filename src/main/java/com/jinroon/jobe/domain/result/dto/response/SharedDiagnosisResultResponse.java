package com.jinroon.jobe.domain.result.dto.response;

import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.global.common.ai.AiGenerationStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 공유 링크(비로그인 공개)용 결과 요약.
 * 공유 화면에 필요한 값만 담는다. 사용자·회차 ID, 공유 토큰, 성향 벡터, 내부 오류 메시지 등은 포함하지 않는다.
 */
@Schema(description = "공유용 진단 결과 요약")
public record SharedDiagnosisResultResponse(
        @Schema(description = "진단 결과 작성자 닉네임") String ownerNickname,
        @Schema(description = "역량 점수 벡터(JSON)") String competencyVector,
        @Schema(description = "AI 추천 코멘트") String aiComment,
        @Schema(description = "AI 코멘트 생성 상태") AiGenerationStatus aiCommentStatus
) {
    public static SharedDiagnosisResultResponse from(DiagnosisResult result, String ownerNickname) {
        return new SharedDiagnosisResultResponse(
                ownerNickname,
                result.getCompetencyVector(),
                result.getAiComment(),
                result.getAiCommentStatus()
        );
    }
}
