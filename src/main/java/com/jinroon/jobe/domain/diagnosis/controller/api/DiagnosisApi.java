package com.jinroon.jobe.domain.diagnosis.controller.api;

import com.jinroon.jobe.domain.diagnosis.dto.response.InProgressSessionResponse;
import com.jinroon.jobe.domain.diagnosis.entity.CompetencyEvalResult;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisEssayAnswer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisExamAnswer;
import com.jinroon.jobe.domain.diagnosis.entity.DiagnosisSession;
import com.jinroon.jobe.domain.diagnosis.entity.ExamQuestion;
import com.jinroon.jobe.domain.diagnosis.entity.TendencyEvalResult;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import com.jinroon.jobe.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "진단 API", description = "역량 진단 세션, 문항, 답변, 평가 결과 관리 API")
public interface DiagnosisApi {

    @Operation(summary = "진행 중인 진단 세션 조회", description = "현재 로그인한 사용자의 진행 중인 진단 세션과 저장된 답변을 반환합니다. 없으면 404.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "진행 중인 세션 반환",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InProgressSessionResponse.class))),
            @ApiResponse(responseCode = "404", description = "진행 중인 세션 없음", content = @Content)
    })
    InProgressSessionResponse getInProgressSession(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails
    );

    @Operation(summary = "진단 세션 단건 조회", description = "세션 ID로 진단 세션 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisSession.class))),
            @ApiResponse(responseCode = "404", description = "진단 세션을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisSession getSession(
            @Parameter(description = "진단 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "내 진단 세션 목록 조회", description = "현재 로그인한 사용자의 진단 세션 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DiagnosisSession.class))))
    })
    List<DiagnosisSession> findSessionsByUser(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails
    );

    @Operation(summary = "전체 시험 문항 조회", description = "등록된 모든 역량 시험 문항을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ExamQuestion.class))))
    })
    List<ExamQuestion> findQuestions();

    @Operation(summary = "시험 문항 단건 조회", description = "문항 ID로 특정 시험 문항을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExamQuestion.class))),
            @ApiResponse(responseCode = "404", description = "문항을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ExamQuestion getQuestion(
            @Parameter(description = "시험 문항 ID") @PathVariable Long questionId
    );

    @Operation(summary = "진단 세션 객관식 답변 목록 조회", description = "진단 세션에 등록된 객관식 답변 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DiagnosisExamAnswer.class))))
    })
    List<DiagnosisExamAnswer> findExamAnswers(
            @Parameter(description = "진단 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "진단 세션 서술형 답변 목록 조회", description = "진단 세션에 등록된 서술형 답변 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DiagnosisEssayAnswer.class))))
    })
    List<DiagnosisEssayAnswer> findEssayAnswers(
            @Parameter(description = "진단 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "역량 평가 결과 조회", description = "진단 세션의 역량 평가 결과를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CompetencyEvalResult.class))),
            @ApiResponse(responseCode = "404", description = "역량 평가 결과를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    CompetencyEvalResult getCompetencyResult(
            @Parameter(description = "진단 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "성향 평가 결과 조회", description = "진단 세션의 성향 평가 결과를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = TendencyEvalResult.class))),
            @ApiResponse(responseCode = "404", description = "성향 평가 결과를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    TendencyEvalResult getTendencyResult(
            @Parameter(description = "진단 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "진단 세션 생성", description = "새로운 진단 세션을 시작합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisSession.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisSession createSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "진단 세션 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "진단 세션 수정", description = "진단 세션 정보(상태, 현재 단계 등)를 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisSession.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "진단 세션을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisSession updateSession(
            @Parameter(description = "진단 세션 ID") @PathVariable Long sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "수정할 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "시험 문항 생성", description = "새로운 역량 시험 문항을 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExamQuestion.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ExamQuestion createQuestion(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "시험 문항 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "객관식 답변 등록", description = "진단 세션에 객관식 문항 답변을 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisExamAnswer.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisExamAnswer createExamAnswer(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "객관식 답변 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "서술형 답변 등록", description = "진단 세션에 서술형 답변을 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisEssayAnswer.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisEssayAnswer createEssayAnswer(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "서술형 답변 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "역량 평가 결과 등록", description = "진단 세션의 역량 평가 결과를 저장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = CompetencyEvalResult.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    CompetencyEvalResult createCompetencyResult(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "역량 평가 결과 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "성향 평가 결과 등록", description = "진단 세션의 성향 평가 결과를 저장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "등록 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = TendencyEvalResult.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    TendencyEvalResult createTendencyResult(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "성향 평가 결과 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );
}
