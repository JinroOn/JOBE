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
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
// init.sql의 uk_users_email과 같은 이름을 써서 ddl-auto가 중복 제약을 만들지 않게 한다.
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_email", columnNames = "email"))
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

    public static User registerEmailUser(String email, String passwordHash, String nickname, String profileImageUrl) {
        User user = new User();
        user.email = email;
        user.passwordHash = passwordHash;
        user.nickname = nickname;
        user.profileImageUrl = profileImageUrl;
        user.role = UserRole.member;
        user.loginType = LoginType.email;
        user.status = UserStatus.active;
        return user;
    }

    /**
     * 관리자 API(POST /api/users)용 생성. 필드를 명시적으로 받아 그 외 값은 설정하지 않는다.
     * role·loginType·status가 없으면 일반 이메일 회원의 기본값을 쓴다.
     */
    public static User createByAdmin(String email, String passwordHash, String nickname, String profileImageUrl,
                                     UserRole role, LoginType loginType, UserStatus status) {
        User user = registerEmailUser(email, passwordHash, nickname, profileImageUrl);
        if (role != null) {
            user.role = role;
        }
        if (loginType != null) {
            user.loginType = loginType;
        }
        if (status != null) {
            user.status = status;
        }
        return user;
    }

    /**
     * 본인 프로필 수정. 닉네임·프로필 이미지만 바꿀 수 있고 null이면 기존 값을 유지한다.
     */
    public void updateProfile(String nickname, String profileImageUrl) {
        if (nickname != null) {
            this.nickname = nickname;
        }
        if (profileImageUrl != null) {
            this.profileImageUrl = profileImageUrl;
        }
    }

    public void recordLogin() {
        this.lastLoginAt = LocalDateTime.now();
    }

    public void verifyEmail() {
        LocalDateTime now = LocalDateTime.now();
        this.emailVerifiedAt = now;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public void withdraw() {
        this.status = UserStatus.withdrawn;
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
