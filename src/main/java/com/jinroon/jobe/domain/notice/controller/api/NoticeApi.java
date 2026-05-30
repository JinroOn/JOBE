package com.jinroon.jobe.domain.notice.controller.api;

import com.jinroon.jobe.domain.notice.entity.Notice;
import com.jinroon.jobe.global.exception.error.ErrorDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

@Tag(name = "공지사항 API", description = "공지사항 조회 및 관리 API")
public interface NoticeApi {

    @Operation(summary = "공지사항 목록 조회", description = "공지사항 목록을 페이지 단위로 반환합니다. activeOnly=true 시 현재 노출 기간에 해당하는 공지만 반환합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Notice.class)))
    })
    Page<Notice> findNotices(
            @Parameter(description = "활성 공지사항만 조회 여부 (기본값: false)") @RequestParam(defaultValue = "false") boolean activeOnly,
            @PageableDefault(size = 10, sort = "id", direction = Sort.Direction.DESC) Pageable pageable
    );

    @Operation(summary = "공지사항 단건 조회", description = "공지사항 ID로 특정 공지사항을 조회합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "조회 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Notice.class))),
            @ApiResponse(responseCode = "404", description = "공지사항을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    Notice getNotice(
            @Parameter(description = "공지사항 ID") @PathVariable Long noticeId
    );

    @Operation(summary = "공지사항 생성", description = "새로운 공지사항을 등록합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "생성 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Notice.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류 또는 기간 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    Notice createNotice(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "공지사항 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "공지사항 수정", description = "공지사항 ID와 변경할 필드값으로 공지사항을 수정합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "수정 성공",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = Notice.class))),
            @ApiResponse(responseCode = "400", description = "입력값 오류 또는 기간 오류",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class))),
            @ApiResponse(responseCode = "404", description = "공지사항을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    Notice updateNotice(
            @Parameter(description = "공지사항 ID") @PathVariable Long noticeId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "수정할 필드값 Map", required = true)
            @RequestBody Map<String, Object> request
    );

    @Operation(summary = "공지사항 삭제", description = "공지사항 ID로 공지사항을 삭제합니다.")
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "삭제 성공", content = @Content),
            @ApiResponse(responseCode = "404", description = "공지사항을 찾을 수 없음",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = ErrorDto.class)))
    })
    void deleteNotice(
            @Parameter(description = "공지사항 ID") @PathVariable Long noticeId
    );
}
