package com.jinroon.jobe.domain.auth.controller.api;

import com.jinroon.jobe.domain.auth.dto.request.ChangePasswordRequest;
import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationConfirmRequest;
import com.jinroon.jobe.domain.auth.dto.request.EmailVerificationIssueRequest;
import com.jinroon.jobe.domain.auth.dto.request.LoginRequest;
import com.jinroon.jobe.domain.auth.dto.request.LogoutRequest;
import com.jinroon.jobe.domain.auth.dto.request.RefreshTokenRequest;
import com.jinroon.jobe.domain.auth.dto.request.SignUpRequest;
import com.jinroon.jobe.domain.auth.dto.response.AuthResponse;
import com.jinroon.jobe.domain.auth.dto.response.EmailVerificationResponse;
import com.jinroon.jobe.domain.user.dto.response.UserResponse;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.RequestBody;

@Tag(name = "인증 API", description = "회원가입, 로그인, 토큰 관리, 이메일 인증 API")
public interface AuthApi {

    @Operation(summary = "회원가입", description = "이메일/비밀번호로 신규 계정을 생성합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "회원가입 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "이미 사용 중인 이메일",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserResponse signUp(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "회원가입 요청 정보", required = true)
            @RequestBody @Valid SignUpRequest request
    );

    @Operation(summary = "로그인", description = "이메일/비밀번호로 로그인하고 Refresh Token을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "로그인 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "이메일 또는 비밀번호 불일치",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    AuthResponse login(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "로그인 요청 정보", required = true)
            @RequestBody @Valid LoginRequest request
    );

    @Operation(summary = "토큰 갱신", description = "Refresh Token으로 새 Refresh Token을 발급받습니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "토큰 갱신 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = AuthResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "유효하지 않거나 만료된 토큰",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    AuthResponse refresh(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "토큰 갱신 요청 정보", required = true)
            @RequestBody @Valid RefreshTokenRequest request
    );

    @Operation(summary = "로그아웃", description = "Refresh Token을 무효화하고 세션을 종료합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "로그아웃 성공", content = @Content),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    void logout(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "로그아웃 요청 정보", required = true)
            @RequestBody @Valid LogoutRequest request
    );

    @Operation(summary = "이메일 인증 토큰 발급", description = "이메일로 인증 토큰을 발급합니다. (30분 유효)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "인증 토큰 발급 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailVerificationResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "존재하지 않는 사용자",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    EmailVerificationResponse issueEmailVerification(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "이메일 인증 발급 요청", required = true)
            @RequestBody @Valid EmailVerificationIssueRequest request
    );

    @Operation(summary = "이메일 인증 확인", description = "발급된 토큰으로 이메일 인증을 완료합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "이메일 인증 완료",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "만료되거나 이미 사용된 토큰",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "인증 정보 또는 사용자를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserResponse confirmEmailVerification(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "이메일 인증 확인 요청", required = true)
            @RequestBody @Valid EmailVerificationConfirmRequest request
    );

    @Operation(summary = "비밀번호 변경", description = "현재 비밀번호 확인 후 새 비밀번호로 변경합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "비밀번호 변경 성공", content = @Content),
            @ApiResponse(responseCode = "400", description = "입력값 유효성 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "401", description = "유효하지 않은 토큰 또는 현재 비밀번호 불일치",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    void changePassword(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "비밀번호 변경 요청", required = true)
            @RequestBody @Valid ChangePasswordRequest request
    );
}
