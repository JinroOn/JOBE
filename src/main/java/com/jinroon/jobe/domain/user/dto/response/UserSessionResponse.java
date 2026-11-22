package com.jinroon.jobe.domain.user.dto.response;

import com.jinroon.jobe.domain.user.entity.Session;
import java.time.LocalDateTime;

/**
 * 로그인 세션 목록 응답. refresh 토큰(해시 포함)은 내보내지 않는다.
 */
public record UserSessionResponse(
        Long id,
        Long userId,
        String deviceInfo,
        LocalDateTime expiresAt,
        LocalDateTime createdAt
) {
    public static UserSessionResponse from(Session session) {
        return new UserSessionResponse(
                session.getId(),
                session.getUserId(),
                session.getDeviceInfo(),
                session.getExpiresAt(),
                session.getCreatedAt()
        );
    }
}
