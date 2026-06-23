package com.jinroon.jobe.domain.result.controller.api;

import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
import com.jinroon.jobe.domain.result.dto.request.DiagnosisResultRequest;
import com.jinroon.jobe.domain.result.dto.request.ResultMajorScoreRequest;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import com.jinroon.jobe.global.security.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "진단 결과 API", description = "진단 결과 및 전공별 점수 관리 API")
public interface ResultApi {

    @Operation(summary = "내 진단 결과 목록 조회", description = "현재 로그인한 사용자의 진단 결과 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DiagnosisResult.class))))
    })
    List<DiagnosisResult> findResults(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails
    );

    @Operation(summary = "진단 결과 단건 조회", description = "결과 ID로 특정 진단 결과를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisResult.class))),
            @ApiResponse(responseCode = "404", description = "진단 결과를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisResult getResult(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "진단 결과 ID") @PathVariable Long resultId
    );

    @Operation(summary = "공유 토큰으로 진단 결과 조회", description = "공유 토큰으로 진단 결과를 조회합니다. 로그인 없이 접근 가능한 공개 API입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisResult.class))),
            @ApiResponse(responseCode = "404", description = "유효하지 않은 공유 토큰",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisResult getSharedResult(
            @Parameter(description = "공유 토큰") @PathVariable String shareToken
    );

    @Operation(summary = "공유 토큰으로 전공별 점수 목록 조회", description = "공유 토큰으로 진단 결과의 전공별 점수를 순위순으로 반환합니다. 로그인 없이 접근 가능한 공개 API입니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ResultMajorScore.class)))),
            @ApiResponse(responseCode = "404", description = "유효하지 않은 공유 토큰",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    List<ResultMajorScore> getSharedMajorScores(
            @Parameter(description = "공유 토큰") @PathVariable String shareToken
    );

    @Operation(summary = "전공별 점수 목록 조회", description = "진단 결과에 포함된 전공별 점수를 순위순으로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ResultMajorScore.class))))
    })
    List<ResultMajorScore> findMajorScores(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "진단 결과 ID") @PathVariable Long resultId
    );

    @Operation(summary = "AI 추천 설명 생성", description = "진단 결과와 전공별 점수를 기반으로 AI 추천 설명을 생성하고 저장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI 추천 설명 생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisResult.class))),
            @ApiResponse(responseCode = "403", description = "본인 진단 결과가 아님",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "진단 결과 또는 전공별 점수를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisResult generateAiComment(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "진단 결과 ID") @PathVariable Long resultId
    );

    @Operation(summary = "진단 결과 생성", description = "진단 세션의 처리 결과를 저장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisResult.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisResult createResult(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "진단 결과 생성 정보",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.result.dto.request.DiagnosisResultRequest.class))
            )
            @Valid @RequestBody DiagnosisResultRequest request
    );

    @Operation(summary = "진단 결과 수정", description = "진단 결과 ID와 변경할 필드값으로 결과를 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisResult.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "진단 결과를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisResult updateResult(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "진단 결과 ID") @PathVariable Long resultId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 필드값",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.result.dto.request.DiagnosisResultRequest.class))
            )
            @Valid @RequestBody DiagnosisResultRequest request
    );

    @Operation(summary = "전공별 점수 생성", description = "진단 결과의 전공별 점수를 저장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ResultMajorScore.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ResultMajorScore createMajorScore(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "전공별 점수 정보",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.result.dto.request.ResultMajorScoreRequest.class))
            )
            @Valid @RequestBody ResultMajorScoreRequest request
    );
}
