package com.jinroon.jobe.global.common.entity;

import com.jinroon.jobe.global.exception.ResourceNotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;

public final class EntityLookup {

    private EntityLookup() {
    }

    public static <T> T get(JpaRepository<T, Long> repository, Long id, String resourceName) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(resourceName, id));
    }
}
