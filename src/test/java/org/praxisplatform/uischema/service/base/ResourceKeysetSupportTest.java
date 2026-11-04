package org.praxisplatform.uischema.service.base;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;
import org.springframework.data.domain.Sort;
import static org.junit.jupiter.api.Assertions.*;

class ResourceKeysetSupportTest {
    record Row(String name, String alternate) { }

    @Test void refusesReuseAcrossPropertiesDirectionsNullOrderingAndCaseSensitivity() {
        Sort sort = Sort.by(Sort.Order.asc("name").nullsLast());
        String cursor = ResourceKeysetSupport.encode(new Row("same type", "another"), sort);
        assertEquals(List.of("same type"), ResourceKeysetSupport.decode(cursor,sort));
        for (Sort incompatible : List.of(Sort.by(Sort.Order.asc("alternate").nullsLast()),
                Sort.by(Sort.Order.desc("name").nullsLast()), Sort.by(Sort.Order.asc("name").nullsFirst()),
                Sort.by(Sort.Order.asc("name").nullsLast().ignoreCase())))
            assertThrows(InvalidFilterPayloadException.class, () -> ResourceKeysetSupport.decode(cursor,incompatible));
    }

    @Test void preservesEscapingNullAndEmptyValuesWithinTheSameSort() {
        var sort = Sort.by("name", "alternate");
        var row = new Row("%:~",null);
        assertEquals(Arrays.asList("%:~",null),ResourceKeysetSupport.decode(ResourceKeysetSupport.encode(row,sort),sort));
        assertEquals(List.of("","text"),ResourceKeysetSupport.decode(ResourceKeysetSupport.encode(new Row("","text"),sort),sort));
        assertThrows(InvalidFilterPayloadException.class, () -> ResourceKeysetSupport.decode("bad?cursor",sort));
    }
}
