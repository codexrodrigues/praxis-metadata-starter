package org.praxisplatform.uischema.service.base;

import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Real H2/JPA repository fallback, without injecting an EntityManager into the service. */
class P5ACursorIndependentReviewTest {
    @BeforeAll static void start() { CoreResourceEvolutionIntegrationTest.start(); }
    @AfterAll static void stop() { CoreResourceEvolutionIntegrationTest.stop(); }
    @Test void nativeNullOrderingMustNotLoseTheRemainingRows() {
        var fixture = new CoreResourceEvolutionIntegrationTest(); fixture.setup();
        try {
            var filter = new CoreResourceEvolutionIntegrationTest.Filter();
            // Control: the EntityManager branch enumerates all four authorized rows.
            assertEquals(4,fixture.service.filterByCursor(filter,Sort.by("name"),null,null,20).content().size());
            ReflectionTestUtils.setField(fixture.service,"entityManager",null);
            List<Long> ids = new ArrayList<>(); String after = null;
            for (int page = 0; page < 8; page++) {
                var result = fixture.service.filterByCursor(filter,Sort.by("name"),after,null,1);
                result.content().forEach(row -> ids.add(row.id));
                after = result.next(); if (after == null) break;
            }
            assertEquals(List.of(1L,2L,3L,4L),ids.stream().sorted().toList(),
                    "each authorized row must appear once across cursor pages; actual="+ids);
        } finally { fixture.rollback(); }
    }
    @Test void explicitNullPrecedenceMustWorkInTheRepositoryBranchToo() {
        var fixture = new CoreResourceEvolutionIntegrationTest(); fixture.setup();
        try {
            ReflectionTestUtils.setField(fixture.service,"entityManager",null);
            assertDoesNotThrow(() -> fixture.service.filterByCursor(new CoreResourceEvolutionIntegrationTest.Filter(),
                    Sort.by(Sort.Order.asc("name").nullsLast()),null,null,1));
        } finally { fixture.rollback(); }
    }

    @Test void repositoryBranchMatchesExplicitOrderingInBothTraversalDirections() {
        var fixture = new CoreResourceEvolutionIntegrationTest(); fixture.setup();
        try {
            var filter = new CoreResourceEvolutionIntegrationTest.Filter();
            for (Sort sort : List.of(Sort.by("name"),Sort.by(Sort.Order.desc("name")),
                    Sort.by(Sort.Order.asc("name").nullsFirst()),Sort.by(Sort.Order.desc("name").nullsLast()))) {
                ReflectionTestUtils.setField(fixture.service,"entityManager",fixture.em);
                var expected = fixture.service.filterByCursor(filter,sort,null,null,10).content().stream().map(row -> row.id).toList();
                ReflectionTestUtils.setField(fixture.service,"entityManager",null);
                List<Long> actual = new ArrayList<>(); String after=null, before=null;
                for (int index=0; index<8; index++) {
                    var page=fixture.service.filterByCursor(filter,sort,after,null,1);
                    page.content().forEach(row -> actual.add(row.id)); before=page.prev(); after=page.next();
                    if (after==null) break;
                }
                assertEquals(expected,actual);
                List<Long> backwards=new ArrayList<>(); backwards.add(actual.getLast());
                for (int index=0; before!=null && index<8; index++) {
                    var page=fixture.service.filterByCursor(filter,sort,null,before,1);
                    page.content().forEach(row -> backwards.add(row.id)); before=page.prev();
                }
                Collections.reverse(backwards); assertEquals(expected,backwards);
            }
        } finally { fixture.rollback(); }
    }
}
