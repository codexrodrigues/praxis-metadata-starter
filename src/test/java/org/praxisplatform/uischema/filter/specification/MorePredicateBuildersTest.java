package org.praxisplatform.uischema.filter.specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MorePredicateBuildersTest {

    CriteriaBuilder cb = Mockito.mock(CriteriaBuilder.class);
    Path<?> path = Mockito.mock(Path.class);
    Expression<Instant> instantExpr = Mockito.mock(Expression.class);
    Expression<? extends Comparable> compExpr = Mockito.mock(Expression.class);
    Expression<Integer> sizeExpr = Mockito.mock(Expression.class);
    Expression<Boolean> boolExpr = Mockito.mock(Expression.class);
    Predicate pred = Mockito.mock(Predicate.class);

    @Test
    void betweenExclusive() {
        when(path.as(Instant.class)).thenReturn(instantExpr);
        when(cb.greaterThan(eq(instantExpr), any(Instant.class))).thenReturn(pred);
        when(cb.lessThan(eq(instantExpr), any(Instant.class))).thenReturn(pred);
        when(cb.and(any(), any())).thenReturn(pred);
        var b = new BetweenExclusivePredicateBuilder();
        Predicate p = b.build(cb, path, List.of(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)));
        assertNotNull(p);
        verify(cb).and(any(), any());
    }

    @Test
    void notBetween() {
        when(path.as(Instant.class)).thenReturn(instantExpr);
        when(cb.between(eq(instantExpr), any(Instant.class), any(Instant.class))).thenReturn(pred);
        when(cb.not(pred)).thenReturn(pred);
        var b = new NotBetweenPredicateBuilder();
        Predicate p = b.build(cb, path, List.of(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)));
        assertNotNull(p);
        verify(cb).not(any(Predicate.class));
    }

    @Test
    void outsideRange() {
        when(path.as(Instant.class)).thenReturn(instantExpr);
        when(cb.lessThan(eq(instantExpr), any(Instant.class))).thenReturn(pred);
        when(cb.greaterThan(eq(instantExpr), any(Instant.class))).thenReturn(pred);
        when(cb.or(any(), any())).thenReturn(pred);
        var b = new OutsideRangePredicateBuilder();
        Predicate p = b.build(cb, path, List.of(LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)));
        assertNotNull(p);
        verify(cb).or(any(), any());
    }

    @Test
    void onDate() {
        when(path.as(Instant.class)).thenReturn(instantExpr);
        when(cb.between(instantExpr, any(Instant.class), any(Instant.class))).thenReturn(pred);
        var b = new OnDatePredicateBuilder();
        Predicate p = b.build(cb, path, LocalDate.of(2024,1,1));
        assertNotNull(p);
        verify(cb).between(eq(instantExpr), any(Instant.class), any(Instant.class));
    }

    @Test
    void inLastDays() {
        when(path.as(Instant.class)).thenReturn(instantExpr);
        when(cb.between(instantExpr, any(Instant.class), any(Instant.class))).thenReturn(pred);
        var b = new InLastDaysPredicateBuilder();
        Predicate p = b.build(cb, path, 7);
        assertNotNull(p);
        verify(cb).between(eq(instantExpr), any(Instant.class), any(Instant.class));
    }

    @Test
    void inNextDays() {
        when(path.as(Instant.class)).thenReturn(instantExpr);
        when(cb.between(instantExpr, any(Instant.class), any(Instant.class))).thenReturn(pred);
        var b = new InNextDaysPredicateBuilder();
        Predicate p = b.build(cb, path, 7);
        assertNotNull(p);
        verify(cb).between(eq(instantExpr), any(Instant.class), any(Instant.class));
    }

    @Test
    void sizeOps() {
        when(cb.size(any(Expression.class))).thenReturn(sizeExpr);
        when(cb.equal(sizeExpr, 3)).thenReturn(pred);
        when(cb.gt(sizeExpr, 3)).thenReturn(pred);
        when(cb.lt(sizeExpr, 3)).thenReturn(pred);
        assertNotNull(new SizeEqPredicateBuilder().build(cb, path, 3));
        assertNotNull(new SizeGtPredicateBuilder().build(cb, path, 3));
        assertNotNull(new SizeLtPredicateBuilder().build(cb, path, 3));
    }

    @Test
    void isTrueIsFalse() {
        when(path.as(Boolean.class)).thenReturn(boolExpr);
        when(cb.isTrue(boolExpr)).thenReturn(pred);
        when(cb.isFalse(boolExpr)).thenReturn(pred);
        assertNotNull(new IsTruePredicateBuilder().build(cb, path, null));
        assertNotNull(new IsFalsePredicateBuilder().build(cb, path, null));
    }
}
