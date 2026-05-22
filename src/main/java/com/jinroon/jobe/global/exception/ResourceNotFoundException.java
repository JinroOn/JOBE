package com.jinroon.jobe.global.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String resourceName, Long id) {
        super(resourceName + " not found. id=" + id);
    }

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
