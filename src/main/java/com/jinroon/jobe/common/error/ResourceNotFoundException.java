package com.jinroon.jobe.common.error;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceName, Long id) {
        super(resourceName + " not found. id=" + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
