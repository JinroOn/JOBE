package com.jinroon.jobe.domain.user.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "user_favorites")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserFavorite extends BaseEntitySupport {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "major_id", nullable = false)
    private Long majorId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
