package org.praxisplatform.uischema.customfields;

import java.math.BigDecimal;
import java.util.*;
import org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation;

final class CustomFieldFixtures {
    static CustomFieldDescriptor field(String key, CustomFieldDataType type) { return field(key, type, FilterOperation.EQUAL); }
    static CustomFieldDescriptor field(String key, CustomFieldDataType type, FilterOperation operation) {
        return new CustomFieldDescriptor(key, key, null, type, null, null, 0, true, false, true, false, true,
                null, new BigDecimal("1"), new BigDecimal("100"), 2, 20, null, null, "Required!",
                (type == CustomFieldDataType.ENUM ? List.of("ab", "cd") : List.of()), List.of(), null, operation, Map.of());
    }
}
