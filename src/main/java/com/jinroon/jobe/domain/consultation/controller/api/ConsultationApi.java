package com.jinroon.jobe.domain.consultation.controller.api;

import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import com.jinroon.jobe.domain.consultation.entity.ConsultationSession;
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

@Tag(name = "상담 API", description = "AI 상담 세션 및 로그 관리 API")
public interface ConsultationApi {

    @Operation(summary = "사용자별 상담 세션 목록 조회", description = "특정 사용자의 상담 세션 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ConsultationSession.class))))
    })
    List<ConsultationSession> findSessionsByUser(
            @Parameter(description = "사용자 ID") @PathVariable Long userId
    );

    @Operation(summary = "상담 세션 단건 조회", description = "세션 ID로 상담 세션 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationSession.class))),
            @ApiResponse(responseCode = "404", description = "상담 세션을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationSession getSession(
            @Parameter(description = "상담 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "상담 로그 목록 조회", description = "상담 세션의 모든 로그를 시간순으로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ConsultationLog.class))))
    })
    List<ConsultationLog> findLogs(
            @Parameter(description = "상담 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "상담 로그 단건 조회", description = "로그 ID로 특정 상담 로그를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationLog.class))),
            @ApiResponse(responseCode = "404", description = "상담 로그를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationLog getLog(
            @Parameter(description = "상담 로그 ID") @PathVariable Long logId
    );

    @Operation(summary = "상담 세션 생성", description = "새로운 상담 세션을 시작합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationSession.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationSession createSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "상담 세션 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "상담 세션 수정", description = "상담 세션 정보를 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationSession.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "상담 세션을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationSession updateSession(
            @Parameter(description = "상담 세션 ID") @PathVariable Long sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "수정할 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "상담 세션 종료", description = "진행 중인 상담 세션을 종료합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "종료 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationSession.class))),
            @ApiResponse(responseCode = "404", description = "상담 세션을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "이미 종료된 상담 세션",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationSession endSession(
            @Parameter(description = "상담 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "상담 로그 추가", description = "진행 중인 상담 세션에 로그(대화 내용)를 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "추가 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationLog.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "이미 종료된 세션에 로그 추가 불가",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationLog createLog(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "상담 로그 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );
}
