package com.jinroon.jobe.common.domain;

import com.jinroon.jobe.common.error.ResourceNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

public final class EntityFormMapper {

    private EntityFormMapper() {
    }

    public static <T> T create(Class<T> type, Map<String, Object> values) {
        try {
            Constructor<T> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            T target = constructor.newInstance();
            apply(target, values);
            return target;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalArgumentException("Cannot create " + type.getSimpleName(), exception);
        }
    }

    public static void apply(Object target, Map<String, Object> values) {
        values.forEach((name, value) -> setField(target, name, value));
    }

    private static void setField(Object target, String name, Object value) {
        Field field = findField(target.getClass(), name);
        if (field == null) {
            throw new ResourceNotFoundException("Unknown field: " + name);
        }
        try {
            field.setAccessible(true);
            field.set(target, convert(value, field.getType()));
        } catch (IllegalAccessException exception) {
            throw new IllegalArgumentException("Cannot set field: " + name, exception);
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convert(Object value, Class<?> targetType) {
        if (value == null || targetType.isInstance(value)) {
            return value;
        }
        if (targetType == String.class) {
            return String.valueOf(value);
        }
        if (targetType == Long.class) {
            return ((Number) value).longValue();
        }
        if (targetType == Integer.class) {
            return ((Number) value).intValue();
        }
        if (targetType == Float.class) {
            return ((Number) value).floatValue();
        }
        if (targetType == Boolean.class) {
            return value instanceof Boolean booleanValue ? booleanValue : Boolean.valueOf(String.valueOf(value));
        }
        if (targetType == BigDecimal.class) {
            return new BigDecimal(String.valueOf(value));
        }
        if (targetType == LocalDateTime.class) {
            return LocalDateTime.parse(String.valueOf(value));
        }
        if (targetType.isEnum()) {
            return Enum.valueOf((Class<Enum>) targetType.asSubclass(Enum.class), String.valueOf(value));
        }
        return value;
    }
}
