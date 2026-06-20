package com.jinroon.jobe.domain.consultation.controller.api;

import com.jinroon.jobe.domain.consultation.entity.ConsultationLog;
import com.jinroon.jobe.domain.consultation.entity.ConsultationSession;
import com.jinroon.jobe.domain.consultation.dto.request.ConsultationLogRequest;
import com.jinroon.jobe.domain.consultation.dto.request.ConsultationMessageRequest;
import com.jinroon.jobe.domain.consultation.dto.request.ConsultationSessionRequest;
import com.jinroon.jobe.domain.consultation.dto.response.ConsultationMessageResponse;
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

@Tag(name = "상담 API", description = "AI 상담 세션 및 로그 관리 API")
public interface ConsultationApi {

    @Operation(summary = "내 상담 세션 목록 조회", description = "현재 로그인한 사용자의 상담 세션 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ConsultationSession.class))))
    })
    List<ConsultationSession> findSessionsByUser(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails
    );

    @Operation(summary = "상담 세션 단건 조회", description = "세션 ID로 상담 세션 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationSession.class))),
            @ApiResponse(responseCode = "404", description = "상담 세션을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationSession getSession(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "상담 세션 ID") @PathVariable Long sessionId
    );

    @Operation(summary = "상담 로그 목록 조회", description = "상담 세션의 모든 로그를 시간순으로 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ConsultationLog.class))))
    })
    List<ConsultationLog> findLogs(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
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
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
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
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "상담 세션 생성 정보",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.consultation.dto.request.ConsultationSessionRequest.class))
            )
            @Valid @RequestBody ConsultationSessionRequest request
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
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "상담 세션 ID") @PathVariable Long sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 세션 필드값",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.consultation.dto.request.ConsultationSessionRequest.class))
            )
            @Valid @RequestBody ConsultationSessionRequest request
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
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
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
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "상담 로그 정보 (role: user | advisor)",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.consultation.dto.request.ConsultationLogRequest.class))
            )
            @Valid @RequestBody ConsultationLogRequest request
    );

    @Operation(summary = "AI consultation message", description = "Stores a user message, generates an AI reply, stores the reply as an assistant log, and returns both logs.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "AI reply generated",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ConsultationMessageResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid input",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "403", description = "Forbidden session",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "Session already ended",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    ConsultationMessageResponse createMessage(
            @Parameter(hidden = true) @AuthenticationPrincipal CustomUserDetails userDetails,
            @Parameter(description = "Consultation session ID") @PathVariable Long sessionId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "User message for AI consultation",
                    required = true,
                    content = @Content(
                            schema = @Schema(implementation = ConsultationMessageRequest.class),
                            examples = @ExampleObject(value = "{\"content\":\"내가 따면 좋을 자격증을 추천해줘\"}")
                    )
            )
            @Valid @RequestBody ConsultationMessageRequest request
    );
}
