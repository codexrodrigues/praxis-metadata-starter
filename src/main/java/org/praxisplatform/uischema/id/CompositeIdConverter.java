package org.praxisplatform.uischema.id;

import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;

import java.util.Set;

/**
 * Spring {@link ConditionalGenericConverter} that converts between canonical wire strings
 * ({@code "1001:1"}) and JPA {@link jakarta.persistence.Embeddable} / {@link CompositeResourceId}
 * composite primary keys in {@code @PathVariable} and {@code @RequestParam} bindings.
 */
public final class CompositeIdConverter implements ConditionalGenericConverter {

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return Set.of(
                new ConvertiblePair(String.class, Object.class),
                new ConvertiblePair(Object.class, String.class)
        );
    }

    @Override
    public boolean matches(TypeDescriptor sourceType, TypeDescriptor targetType) {
        Class<?> sourceClass = sourceType.getType();
        Class<?> targetClass = targetType.getType();
        if (sourceClass == String.class && CompositeIdSupport.isCompositeIdType(targetClass)) {
            return true;
        }
        return CompositeIdSupport.isCompositeIdType(sourceClass) && targetClass == String.class;
    }

    @Override
    public Object convert(Object source, TypeDescriptor sourceType, TypeDescriptor targetType) {
        if (source == null) {
            return null;
        }
        Class<?> targetClass = targetType.getType();
        if (source instanceof String wire && CompositeIdSupport.isCompositeIdType(targetClass)) {
            return CompositeIdSupport.decode(wire, targetClass);
        }
        if (targetClass == String.class && CompositeIdSupport.isCompositeIdValue(source)) {
            return CompositeIdSupport.encode(source);
        }
        throw new IllegalArgumentException(
                "Unsupported composite ID conversion from " + sourceType + " to " + targetType
        );
    }
}
