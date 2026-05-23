package com.jinroon.jobe.domain.user.dto.response;

import com.jinroon.jobe.domain.user.entity.Session;
import java.time.LocalDateTime;

public record UserSessionResponse(
        Long id,
        Long userId,
        String refreshToken,
        String deviceInfo,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
    public static UserSessionResponse from(Session session) {
        return new UserSessionResponse(
                session.getId(),
                session.getUserId(),
                session.getRefreshToken(),
                session.getDeviceInfo(),
                session.getExpiresAt(),
                session.getCreatedAt()
        );
    }
}
