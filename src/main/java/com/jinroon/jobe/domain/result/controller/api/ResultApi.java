package com.jinroon.jobe.domain.result.controller.api;

import com.jinroon.jobe.domain.result.entity.DiagnosisResult;
import com.jinroon.jobe.domain.result.entity.ResultMajorScore;
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
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "진단 결과 API", description = "진단 결과 및 전공별 점수 관리 API")
public interface ResultApi {

    @Operation(summary = "진단 결과 목록 조회", description = "userId 파라미터가 있으면 특정 사용자의 결과만, 없으면 전체를 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = DiagnosisResult.class))))
    })
    List<DiagnosisResult> findResults(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "사용자 ID (선택)") @RequestParam(required = false) Long userId
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

    @Operation(summary = "진단 결과 생성", description = "진단 세션의 처리 결과를 저장합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = DiagnosisResult.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    DiagnosisResult createResult(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "진단 결과 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "수정할 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "전공별 점수 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );
}
