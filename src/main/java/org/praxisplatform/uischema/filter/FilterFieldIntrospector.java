package org.praxisplatform.uischema.filter;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Internal field discovery shared by query execution and payload normalization. */
public final class FilterFieldIntrospector {
    private FilterFieldIntrospector() { }

    /**
     * Returns instance fields from the concrete DTO through its superclasses.
     * A declaration in a more specific type shadows a parent field with the same
     * name, including when the new declaration is not annotated as a filter.
     * Static and synthetic fields are never request criteria.
     *
     * @param type concrete filter DTO type
     * @return immutable fields in child-before-parent declaration order
     */
    public static List<Field> fields(Class<?> type) {
        Map<String, Field> fields = new LinkedHashMap<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (!field.isSynthetic() && !Modifier.isStatic(field.getModifiers())) {
                    fields.putIfAbsent(field.getName(), field);
                }
            }
        }
        return List.copyOf(fields.values());
    }
}
