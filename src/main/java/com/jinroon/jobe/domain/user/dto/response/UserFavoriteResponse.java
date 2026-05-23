package com.jinroon.jobe.domain.user.dto.response;

import com.jinroon.jobe.domain.user.entity.UserFavorite;
import java.time.LocalDateTime;

public record UserFavoriteResponse(
        Long id,
        Long userId,
        Long majorId,
        LocalDateTime createdAt
) {
    public static UserFavoriteResponse from(UserFavorite favorite) {
        return new UserFavoriteResponse(
                favorite.getId(),
                favorite.getUserId(),
                favorite.getMajorId(),
                favorite.getCreatedAt()
        );
    }
}
