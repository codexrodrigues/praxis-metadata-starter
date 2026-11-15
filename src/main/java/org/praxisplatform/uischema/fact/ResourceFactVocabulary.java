package org.praxisplatform.uischema.fact;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/** Package-private lookup and normalization support for canonical {@code @ResourceFact} vocabularies. */
final class ResourceFactVocabulary {

    private ResourceFactVocabulary() {
    }

    static <E extends Enum<E>> Set<String> allowedWireValues(E[] values, Function<E, String> extractor) {
        Set<String> wireValues = new LinkedHashSet<>(values.length);
        for (E value : values) {
            wireValues.add(extractor.apply(value));
        }
        return Collections.unmodifiableSet(wireValues);
    }

    static <E extends Enum<E>> Map<String, E> indexByWireValue(E[] values, Function<E, String> extractor) {
        Map<String, E> index = new LinkedHashMap<>(values.length);
        for (E value : values) {
            index.put(extractor.apply(value), value);
        }
        return Collections.unmodifiableMap(index);
    }

    static <E extends Enum<E>> Optional<E> fromWireValue(Map<String, E> index, String raw) {
        String normalized = normalizeWireValue(raw);
        return normalized.isEmpty() ? Optional.empty() : Optional.ofNullable(index.get(normalized));
    }

    static boolean isValidWireValue(Set<String> allowed, String raw) {
        String normalized = normalizeWireValue(raw);
        return !normalized.isEmpty() && allowed.contains(normalized);
    }

    static String normalizeWireValue(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
    }
}
