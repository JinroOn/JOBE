package com.jinroon.jobe.user.dto;

import com.jinroon.jobe.user.domain.User;
import java.time.LocalDateTime;

public record UserResponse(
        Long id,
        String email,
        String nickname,
        String profileImageUrl,
        String role,
        String loginType,
        String status,
        LocalDateTime emailVerifiedAt,
        LocalDateTime lastLoginAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getNickname(),
                user.getProfileImageUrl(),
                user.getRoleName(),
                user.getLoginTypeName(),
                user.getStatusName(),
                user.getEmailVerifiedAt(),
                user.getLastLoginAt(),
                user.getCreatedAt(),
                user.getUpdatedAt()
        );
    }
}
