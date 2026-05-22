package com.jinroon.jobe.domain.user.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserRole;
import com.jinroon.jobe.domain.user.enums.UserEnums.LoginType;
import com.jinroon.jobe.domain.user.enums.UserEnums.UserStatus;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends BaseEntitySupport {

    @Column(nullable = false, length = 255)
    private String email;

    @Column(name = "password_hash", length = 255)
    @JsonIgnore
    private String passwordHash;

    @Column(nullable = false, length = 50)
    private String nickname;

    @Column(name = "profile_image_url", length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(name = "login_type", nullable = false, length = 20)
    private LoginType loginType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static User registerEmailUser(String email, String passwordHash, String nickname, String profileImageUrl) {
        LocalDateTime now = LocalDateTime.now();
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.nickname = nickname;
        user.profileImageUrl = profileImageUrl;
        user.role = UserRole.member;
        user.loginType = LoginType.email;
        user.status = UserStatus.active;
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public void recordLogin() {
        LocalDateTime now = LocalDateTime.now();
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public void verifyEmail() {
        LocalDateTime now = LocalDateTime.now();
        this.emailVerifiedAt = now;
        this.updatedAt = now;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.updatedAt = LocalDateTime.now();
    }

    public void withdraw() {
        this.status = UserStatus.withdrawn;
        this.updatedAt = LocalDateTime.now();
    }

    public boolean isActive() {
        return status == UserStatus.active;
    }

    public String getRoleName() {
        return role.name();
    }

    public String getLoginTypeName() {
        return loginType.name();
    }

    public String getStatusName() {
        return status.name();
    }
}
