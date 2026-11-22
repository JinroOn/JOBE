package com.jinroon.jobe.global.common.dto;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RequestMapMapper {

    private RequestMapMapper() {
    }

    public static Map<String, Object> toMap(Object request) {
        Map<String, Object> values = new LinkedHashMap<>();
        Class<?> current = request.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(request);
                    if (value != null) {
                        values.put(field.getName(), value);
                    }
                } catch (IllegalAccessException exception) {
                    throw new IllegalArgumentException("Cannot read request field: " + field.getName(), exception);
                }
            }
            current = current.getSuperclass();
        }
        return values;
    }
}
