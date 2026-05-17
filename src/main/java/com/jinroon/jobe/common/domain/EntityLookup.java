package com.jinroon.jobe.common.domain;

import com.jinroon.jobe.common.error.ResourceNotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;

public final class EntityLookup {

    private EntityLookup() {
    }

    public static <T> T get(JpaRepository<T, Long> repository, Long id, String resourceName) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException(resourceName, id));
    }
}
