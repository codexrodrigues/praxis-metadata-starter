package org.praxisplatform.uischema.bulk;

import java.util.HashSet;
import java.util.List;

final class BulkContractChecks {
    private BulkContractChecks() { }
    static void text(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
    static <T> List<T> nonEmpty(List<T> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("A nonempty collection is required");
        return List.copyOf(values);
    }
    static void unique(List<?> identities) {
        if (new HashSet<>(identities).size() != identities.size())
            throw new IllegalArgumentException("Duplicate identities are not allowed");
    }
    static List<BulkFieldChange> changes(List<BulkFieldChange> values) {
        var copy = nonEmpty(values);
        unique(copy.stream().map(BulkFieldChange::field).toList());
        return copy;
    }
}
