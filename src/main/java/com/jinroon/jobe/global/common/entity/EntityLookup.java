package com.jinroon.jobe.global.common.entity;

import com.jinroon.jobe.global.exception.CustomException;
import com.jinroon.jobe.global.exception.error.ErrorCode;
import org.springframework.data.jpa.repository.JpaRepository;

public final class EntityLookup {

    private EntityLookup() {
    }

    public static <T> T get(JpaRepository<T, Long> repository, Long id, ErrorCode errorCode) {
        return repository.findById(id)
                .orElseThrow(() -> new CustomException(errorCode));
    }
}
