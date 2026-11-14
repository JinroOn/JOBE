package com.jinroon.jobe.domain.notice.entity;

import com.jinroon.jobe.global.common.entity.BaseEntitySupport;
import com.jinroon.jobe.domain.notice.enums.NoticeEnums.NoticeDisplayType;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notices")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notice extends BaseEntitySupport {

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(nullable = false, length = 200)
    private String title;

    @Lob
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "display_type", nullable = false, length = 20)
    private NoticeDisplayType displayType;

    @Column(name = "start_at", nullable = false)
    private LocalDateTime startAt;

    @Column(name = "end_at", nullable = false)
    private LocalDateTime endAt;

    public static Notice create(Long createdBy, String title, String content, NoticeDisplayType displayType,
                                LocalDateTime startAt, LocalDateTime endAt) {
        Notice notice = new Notice();
        notice.createdBy = createdBy;
        notice.title = title;
        notice.content = content;
        notice.displayType = displayType;
        notice.startAt = startAt;
        notice.endAt = endAt;
        return notice;
    }

    /**
     * 부분 수정. null인 값은 기존 값을 유지한다. 작성자(createdBy)는 바꾸지 않는다.
     */
    public void update(String title, String content, NoticeDisplayType displayType,
                       LocalDateTime startAt, LocalDateTime endAt) {
        if (title != null) {
            this.title = title;
        }
        if (content != null) {
            this.content = content;
        }
        if (displayType != null) {
            this.displayType = displayType;
        }
        if (startAt != null) {
            this.startAt = startAt;
        }
        if (endAt != null) {
            this.endAt = endAt;
        }
    }

    /** 게시 시작 시각이 지났는지(종료 여부와 무관). 일반 사용자에게 보여줄 수 있는 공지인지 판단한다. */
    public boolean isPublished(LocalDateTime now) {
        return !startAt.isAfter(now);
    }

    public boolean hasValidDisplayPeriod() {
        return !endAt.isBefore(startAt);
    }
}
