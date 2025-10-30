package org.praxisplatform.uischema.extension;

import java.lang.annotation.*;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Utilitários para resolução de anotações, incluindo busca de meta-anotações.
 * Evita loops e reprocessamentos através de um conjunto de anotações já visitadas.
 *
 * @since 1.0.0
 */
public class ResolverUtils {
    private ResolverUtils() {
    }

    /**
     * Busca por uma anotação do tipo desejado na lista informada, percorrendo
     * meta-anotações quando necessário.
     *
     * @param cls tipo da anotação alvo
     * @param annotations anotações a inspecionar
     * @return instância da anotação encontrada ou {@code null}
     */
    public static <T> T getAnnotation(Class<T> cls, Annotation... annotations) {
        return getAnnotation(cls, buildVisitedSet(), annotations);
    }

    private static <T> T getAnnotation(Class<T> cls, Set<Class<?>> visited, Annotation... annotations) {
        if (annotations == null) {
            return null;
        }
        for (Annotation annotation : annotations) {
            if (cls.isAssignableFrom(annotation.getClass())) {
                return cls.cast(annotation);
            }
            if (!visited.contains(annotation.getClass())) {
                visited.add(annotation.getClass());
                T meta = getAnnotation(cls, visited, annotation.annotationType().getAnnotations());
                if (meta != null) return meta;
            }
        }
        return null;
    }

    private static Set<Class<?>> buildVisitedSet() {
        return Stream.of(Target.class, Retention.class, Inherited.class, Documented.class)
                .collect(Collectors.toSet());
    }
}
