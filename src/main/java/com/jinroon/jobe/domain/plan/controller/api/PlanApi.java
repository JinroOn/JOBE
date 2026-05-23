package com.jinroon.jobe.domain.plan.controller.api;

import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.global.exception.error.ErrorDto;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "학습 계획 API", description = "주차별 학습 계획, 항목, 리스크 노트 관리 API")
public interface PlanApi {

    @Operation(summary = "학습 계획 단건 조회", description = "계획 ID로 주차별 학습 계획을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlan.class))),
            @ApiResponse(responseCode = "404", description = "학습 계획을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlan getPlan(
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId
    );

    @Operation(summary = "진단 결과별 학습 계획 목록 조회", description = "진단 결과 ID에 연결된 학습 계획 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = MajorWeeklyPlan.class))))
    })
    List<MajorWeeklyPlan> findPlansByResult(
            @Parameter(description = "진단 결과 ID") @PathVariable Long resultId
    );

    @Operation(summary = "학습 계획 항목 목록 조회", description = "학습 계획에 속한 주차별 항목을 순서대로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = MajorWeeklyPlanItem.class))))
    })
    List<MajorWeeklyPlanItem> findItems(
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId
    );

    @Operation(summary = "리스크 노트 목록 조회", description = "학습 계획에 속한 리스크 노트 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = MajorWeeklyPlanRiskNote.class))))
    })
    List<MajorWeeklyPlanRiskNote> findRiskNotes(
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId
    );

    @Operation(summary = "학습 계획 생성", description = "새로운 주차별 학습 계획을 생성합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlan.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlan createPlan(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "학습 계획 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "학습 계획 수정", description = "학습 계획 ID와 변경할 필드값으로 계획을 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlan.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "학습 계획을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlan updatePlan(
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "수정할 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "학습 계획 항목 생성", description = "학습 계획에 주차별 항목을 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlanItem.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlanItem createItem(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "학습 계획 항목 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "리스크 노트 생성", description = "학습 계획에 리스크 노트를 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlanRiskNote.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlanRiskNote createRiskNote(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "리스크 노트 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );
}
