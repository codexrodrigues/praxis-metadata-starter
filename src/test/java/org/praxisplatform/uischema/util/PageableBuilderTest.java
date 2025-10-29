package org.praxisplatform.uischema.util;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PageableBuilderTest {

    @Test
    void usesFallbackWhenSortMissing() {
        Pageable p = PageableBuilder.from(0, 10, null, Sort.by("name"));
        assertEquals(Sort.Direction.ASC, p.getSort().getOrderFor("name").getDirection());
    }

    @Test
    void parsesSortParameters() {
        Pageable p = PageableBuilder.from(1, 5, List.of("name,desc", "id,asc"), Sort.unsorted());
        assertEquals(1, p.getPageNumber());
        assertEquals(Sort.Direction.DESC, p.getSort().getOrderFor("name").getDirection());
        assertEquals(Sort.Direction.ASC, p.getSort().getOrderFor("id").getDirection());
    }
}
