package org.praxisplatform.uischema.filter.range;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RangeBoundAliasRegistryTest {

    @Test
    void monetaryAliasesExposeCanonicalBounds() {
        assertTrue(RangeBoundAliasRegistry.lowerMoneyKeys().contains("minPrice"));
        assertTrue(RangeBoundAliasRegistry.upperMoneyKeys().contains("maxPrice"));
        assertTrue(RangeBoundAliasRegistry.lowerMoneyKeys().contains("valorMin"));
        assertTrue(RangeBoundAliasRegistry.upperMoneyKeys().contains("valorMax"));
    }

    @Test
    void aliasListsAreImmutable() {
        assertThrows(
                UnsupportedOperationException.class,
                () -> RangeBoundAliasRegistry.lowerMoneyKeys().add("another")
        );
        assertThrows(
                UnsupportedOperationException.class,
                () -> RangeBoundAliasRegistry.upperGenericKeys().add("another")
        );
    }
}
