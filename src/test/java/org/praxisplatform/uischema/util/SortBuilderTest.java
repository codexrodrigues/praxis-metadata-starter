package org.praxisplatform.uischema.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SortBuilderTest {

    @Test
    void ignoresBlankEntries() {
        Sort s = SortBuilder.from(List.of("", "  "), Sort.by("fallback"));
        assertEquals(Sort.Direction.ASC, s.getOrderFor("fallback").getDirection());
        assertNull(s.getOrderFor(""));
    }

    @Test
    void defaultsToAscOnInvalidDirection() {
        Sort s = SortBuilder.from(List.of("name,invalid"), Sort.unsorted());
        assertEquals(Sort.Direction.ASC, s.getOrderFor("name").getDirection());
    }
}
