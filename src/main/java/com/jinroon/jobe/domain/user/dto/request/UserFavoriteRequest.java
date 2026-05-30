package com.jinroon.jobe.domain.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "즐겨찾기 전공 추가 데이터")
public class UserFavoriteRequest {

    @Schema(description = "추가할 전공 ID", example = "1")
    private Long majorId;
}
