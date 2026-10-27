package org.praxisplatform.uischema.exporting;

/**
 * Resolve o valor de uma coluna exportavel para uma linha de recurso.
 *
 * <p>O recurso continua dono das regras de acesso, joins e campos derivados; os engines
 * canonicos cuidam apenas da serializacao do formato solicitado.</p>
 */
@FunctionalInterface
public interface CollectionExportValueResolver<T> {

    Object resolve(T row, CollectionExportField field);

    /** Opt-in reader for an already authorized set of export columns. */
    static <T> CollectionExportValueResolver<T> reflective() {
        return (row, field) -> readFieldValue(row,
                field.valuePath() == null || field.valuePath().isBlank() ? field.key() : field.valuePath());
    }

    /** Traverses bean/record properties and Map entries, preserving nulls. */
    static Object readFieldValue(Object row, String fieldKey) {
        if (row == null || fieldKey == null || fieldKey.isBlank()) return null;
        Object value = row;
        for (String segment : fieldKey.split("\\.", -1)) {
            if (segment.isEmpty()) throw new IllegalArgumentException("Empty export path segment");
            if (value == null) return null;
            if (value instanceof java.util.Map<?, ?> map) { value = map.get(segment); continue; }
            if ("class".equals(segment)) throw new IllegalArgumentException("Not an export property");
            try {
                java.lang.reflect.Method accessor = null;
                if (value.getClass().isRecord()) {
                    for (var component : value.getClass().getRecordComponents()) {
                        if (component.getName().equals(segment)) { accessor = component.getAccessor(); break; }
                    }
                } else {
                    var property = org.springframework.beans.BeanUtils.getPropertyDescriptor(value.getClass(), segment);
                    if (property != null) accessor = property.getReadMethod();
                }
                if (accessor == null) throw new IllegalArgumentException("Unreadable export property: " + segment);
                accessor.trySetAccessible();
                value = accessor.invoke(value);
            } catch (ReflectiveOperationException ex) { throw new IllegalArgumentException("Cannot read export property", ex); }
        }
        return value;
    }
}
