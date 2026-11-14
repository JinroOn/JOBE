package com.jinroon.jobe.domain.user.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(
        name = "user_favorites",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_user_favorites_user_major",
                columnNames = {"user_id", "major_id"}
        )
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFavorite extends BaseEntitySupport {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "major_id", nullable = false)
    private Long majorId;

    public static UserFavorite of(Long userId, Long majorId) {
        UserFavorite favorite = new UserFavorite();
        favorite.userId = userId;
        favorite.majorId = majorId;
        return favorite;
    }
}
