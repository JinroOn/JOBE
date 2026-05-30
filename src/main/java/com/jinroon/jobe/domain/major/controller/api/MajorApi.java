package com.jinroon.jobe.domain.major.controller.api;

import com.jinroon.jobe.domain.major.entity.Major;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "전공 API", description = "전공 정보 조회 및 관리 API")
public interface MajorApi {

    @Operation(summary = "전공 목록 조회", description = "카테고리 또는 키워드로 전공을 검색합니다. 파라미터 없으면 전체 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = Major.class))))
    })
    List<Major> findMajors(
            @Parameter(description = "카테고리 필터 (예: IT, 의학)") @RequestParam(required = false) String category,
            @Parameter(description = "전공명 키워드 검색") @RequestParam(required = false) String keyword
    );

    @Operation(summary = "전공 단건 조회", description = "전공 ID로 특정 전공 정보를 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Major.class))),
            @ApiResponse(responseCode = "404", description = "전공을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    Major getMajor(
            @Parameter(description = "전공 ID") @PathVariable Long majorId
    );

    @Operation(summary = "전공 생성", description = "새로운 전공을 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Major.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    Major createMajor(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "전공 생성 정보",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.major.dto.request.MajorRequest.class))
            )
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "전공 수정", description = "전공 ID와 변경할 필드값으로 전공 정보를 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Major.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "전공을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    Major updateMajor(
            @Parameter(description = "전공 ID") @PathVariable Long majorId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    description = "수정할 필드값",
                    required = true,
                    content = @Content(schema = @Schema(implementation = com.jinroon.jobe.domain.major.dto.request.MajorRequest.class))
            )
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "전공 삭제", description = "전공 ID로 전공을 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공", content = @Content),
            @ApiResponse(responseCode = "404", description = "전공을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    void deleteMajor(
            @Parameter(description = "전공 ID") @PathVariable Long majorId
    );
}
