package com.jinroon.jobe.domain.user.controller.api;

import com.jinroon.jobe.domain.auth.dto.response.EmailVerificationResponse;
import com.jinroon.jobe.domain.user.dto.response.UserConsentResponse;
import com.jinroon.jobe.domain.user.dto.response.UserFavoriteResponse;
import com.jinroon.jobe.domain.user.dto.response.UserResponse;
import com.jinroon.jobe.domain.user.dto.response.UserSessionResponse;
import com.jinroon.jobe.domain.user.entity.User;
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

@Tag(name = "사용자 API", description = "사용자 정보, 동의, 즐겨찾기, 세션, 이메일 인증 관리 API")
public interface UserApi {

    @Operation(summary = "전체 사용자 조회", description = "모든 사용자 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = User.class))))
    })
    List<User> findUsers();

    @Operation(summary = "사용자 단건 조회", description = "ID로 특정 사용자를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserResponse getUser(
            @Parameter(description = "사용자 ID") @PathVariable Long userId
    );

    @Operation(summary = "사용자 동의 내역 조회", description = "특정 사용자의 약관 동의 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserConsentResponse.class))))
    })
    List<UserConsentResponse> findConsents(
            @Parameter(description = "사용자 ID") @PathVariable Long userId
    );

    @Operation(summary = "사용자 즐겨찾기 전공 조회", description = "특정 사용자의 즐겨찾기 전공 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserFavoriteResponse.class))))
    })
    List<UserFavoriteResponse> findFavorites(
            @Parameter(description = "사용자 ID") @PathVariable Long userId
    );

    @Operation(summary = "사용자 세션 목록 조회", description = "특정 사용자의 활성 세션 목록을 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = UserSessionResponse.class))))
    })
    List<UserSessionResponse> findSessions(
            @Parameter(description = "사용자 ID") @PathVariable Long userId
    );

    @Operation(summary = "이메일 인증 정보 단건 조회", description = "ID로 이메일 인증 레코드를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailVerificationResponse.class))),
            @ApiResponse(responseCode = "404", description = "인증 정보를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    EmailVerificationResponse getEmailVerification(
            @Parameter(description = "이메일 인증 ID") @PathVariable Long verificationId
    );

    @Operation(summary = "사용자 생성", description = "필드값 Map으로 사용자를 직접 생성합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserResponse createUser(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "사용자 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "사용자 정보 수정", description = "사용자 ID와 변경할 필드값 Map으로 정보를 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserResponse updateUser(
            @Parameter(description = "사용자 ID") @PathVariable Long userId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "수정할 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "사용자 삭제", description = "사용자 ID로 사용자를 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공", content = @Content),
            @ApiResponse(responseCode = "404", description = "사용자를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    void deleteUser(
            @Parameter(description = "사용자 ID") @PathVariable Long userId
    );

    @Operation(summary = "약관 동의 생성", description = "사용자 약관 동의 레코드를 생성합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserConsentResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserConsentResponse createConsent(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "동의 정보 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "즐겨찾기 전공 추가", description = "사용자의 즐겨찾기 전공을 추가합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "추가 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserFavoriteResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "사용자 또는 전공을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "409", description = "이미 즐겨찾기에 추가된 전공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserFavoriteResponse createFavorite(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "즐겨찾기 정보 (userId, majorId)", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "즐겨찾기 전공 삭제", description = "즐겨찾기 ID로 즐겨찾기를 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공", content = @Content),
            @ApiResponse(responseCode = "404", description = "즐겨찾기를 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    void deleteFavorite(
            @Parameter(description = "즐겨찾기 ID") @PathVariable Long favoriteId
    );

    @Operation(summary = "세션 생성", description = "사용자 세션 레코드를 생성합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = UserSessionResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    UserSessionResponse createSession(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "세션 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "이메일 인증 레코드 생성", description = "이메일 인증 레코드를 직접 생성합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = EmailVerificationResponse.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    EmailVerificationResponse createEmailVerification(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "이메일 인증 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );
}
