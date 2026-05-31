package com.jinroon.jobe.domain.notice.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "공지사항 생성 및 수정 요청 데이터")
public class NoticeRequest {

    @Schema(description = "작성자 ID", example = "1")
    private Long createdBy;

    @Schema(description = "공지사항 제목", example = "시스템 점검 안내")
    @Size(max = 200)
    private String title;

    @Schema(description = "공지사항 내용", example = "서버 안정화를 위한 시스템 점검이 있을 예정입니다.")
    @Size(max = 10000)
    private String content;

    @Schema(description = "노출 타입 (banner, popup)", example = "banner")
    private String displayType;

    @Schema(description = "노출 시작일시", example = "2026-06-01T00:00:00")
    private LocalDateTime startAt;

    @Schema(description = "노출 종료일시", example = "2026-06-30T23:59:59")
    private LocalDateTime endAt;
}
