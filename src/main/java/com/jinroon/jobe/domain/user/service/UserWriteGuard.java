package com.jinroon.jobe.domain.user.service;

import com.jinroon.jobe.domain.user.repository.UserRepository;
import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Serializes personal writes with withdrawal. Acquire before child/session/result locks. */
@Service
@RequiredArgsConstructor
public class UserWriteGuard {
    private final UserRepository users;

    @Transactional(propagation = Propagation.MANDATORY)
    public void requireActive(Long userId) {
        var user = users.findByIdForUpdate(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
        if (!user.isActive()) throw new CustomException(ErrorCode.UNAUTHORIZED);
    }
}
