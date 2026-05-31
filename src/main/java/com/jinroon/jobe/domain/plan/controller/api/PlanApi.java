package com.jinroon.jobe.domain.plan.controller.api;

import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlan;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanItem;
import com.jinroon.jobe.domain.plan.entity.MajorWeeklyPlanRiskNote;
import com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanItemRequest;
import com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanRequest;
import com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanRiskNoteRequest;
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
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId
    );

    @Operation(summary = "진단 결과별 학습 계획 목록 조회", description = "진단 결과 ID에 연결된 학습 계획 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = MajorWeeklyPlan.class))))
    })
    List<MajorWeeklyPlan> findPlansByResult(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "진단 결과 ID") @PathVariable Long resultId
    );

    @Operation(summary = "학습 계획 항목 목록 조회", description = "학습 계획에 속한 주차별 항목을 순서대로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = MajorWeeklyPlanItem.class))))
    })
    List<MajorWeeklyPlanItem> findItems(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId
    );

    @Operation(summary = "리스크 노트 목록 조회", description = "학습 계획에 속한 리스크 노트 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = MajorWeeklyPlanRiskNote.class))))
    })
    List<MajorWeeklyPlanRiskNote> findRiskNotes(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId
    );

    @Operation(summary = "학습 계획 생성", description = "새로운 주차별 학습 계획을 생성합니다. AI 서버에서 주간 계획이 자동으로 생성됩니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlan.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlan createPlan(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "학습 계획 생성 정보",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanRequest.class))
            )
            @Valid @RequestBody MajorWeeklyPlanRequest request
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
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "학습 계획 ID") @PathVariable Long planId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 필드값",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanRequest.class))
            )
            @Valid @RequestBody MajorWeeklyPlanRequest request
    );

    @Operation(summary = "학습 계획 항목 생성", description = "학습 계획에 주차별 항목을 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlanItem.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlanItem createItem(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "주차별 학습 항목 정보",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanItemRequest.class))
            )
            @Valid @RequestBody MajorWeeklyPlanItemRequest request
    );

    @Operation(summary = "주차 학습 항목 완료 처리", description = "해당 주차 항목을 완료 상태로 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "완료 처리 성공", content = @Content),
            @ApiResponse(responseCode = "404", description = "항목을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    void completeItem(
            @Parameter(description = "학습 계획 항목 ID") @PathVariable Long id,
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails
    );

    @Operation(summary = "리스크 노트 생성", description = "학습 계획에 리스크 노트를 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = MajorWeeklyPlanRiskNote.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    MajorWeeklyPlanRiskNote createRiskNote(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "리스크 노트 정보",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.plan.dto.request.MajorWeeklyPlanRiskNoteRequest.class))
            )
            @Valid @RequestBody MajorWeeklyPlanRiskNoteRequest request
    );
}
