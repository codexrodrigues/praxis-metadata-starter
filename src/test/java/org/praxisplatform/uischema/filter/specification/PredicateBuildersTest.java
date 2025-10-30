package org.praxisplatform.uischema.filter.specification;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.praxisplatform.uischema.filter.annotation.Filterable;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PredicateBuildersTest {

    @Mock
    CriteriaBuilder cb;
    @Mock
    Path<?> path;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    @DisplayName("NOT_EQUAL -> criteriaBuilder.notEqual(path, value)")
    void notEqualPredicate() {
        Predicate expected = mock(Predicate.class);
        when(cb.notEqual(path, "x")).thenReturn(expected);

        PredicateBuilder b = new NotEqualPredicateBuilder();
        Predicate p = b.build(cb, path, "x");
        assertSame(expected, p);
        verify(cb).notEqual(path, "x");
    }

    @Test
    @DisplayName("NOT_LIKE -> NOT(LOWER(path) LIKE %value%)")
    @SuppressWarnings("unchecked")
    void notLikePredicate() {
        @SuppressWarnings("rawtypes") Expression lowerExpr = mock(Expression.class);
        @SuppressWarnings("rawtypes") Expression stringExpr = mock(Expression.class);
        when(path.as(String.class)).thenReturn(stringExpr);
        when(cb.lower(stringExpr)).thenReturn(lowerExpr);
        Predicate like = mock(Predicate.class);
        when(cb.like(lowerExpr, "%abc%"))
                .thenReturn(like);
        Predicate expected = mock(Predicate.class);
        when(cb.not(like)).thenReturn(expected);

        PredicateBuilder b = new NotLikePredicateBuilder();
        Predicate p = b.build(cb, path, "abc");
        assertSame(expected, p);
        verify(cb).lower(stringExpr);
        verify(cb).like(lowerExpr, "%abc%");
        verify(cb).not(like);
    }

    @Test
    @DisplayName("STARTS_WITH -> LOWER(path) LIKE value%")
    @SuppressWarnings("unchecked")
    void startsWithPredicate() {
        @SuppressWarnings("rawtypes") Expression stringExpr = mock(Expression.class);
        @SuppressWarnings("rawtypes") Expression lowerExpr = mock(Expression.class);
        when(path.as(String.class)).thenReturn(stringExpr);
        when(cb.lower(stringExpr)).thenReturn(lowerExpr);
        Predicate expected = mock(Predicate.class);
        when(cb.like(lowerExpr, "pre%"))
                .thenReturn(expected);

        PredicateBuilder b = new StartsWithPredicateBuilder();
        Predicate p = b.build(cb, path, "Pre");
        assertSame(expected, p);
        verify(cb).like(lowerExpr, "pre%");
    }

    @Test
    @DisplayName("ENDS_WITH -> LOWER(path) LIKE %value")
    @SuppressWarnings("unchecked")
    void endsWithPredicate() {
        @SuppressWarnings("rawtypes") Expression stringExpr = mock(Expression.class);
        @SuppressWarnings("rawtypes") Expression lowerExpr = mock(Expression.class);
        when(path.as(String.class)).thenReturn(stringExpr);
        when(cb.lower(stringExpr)).thenReturn(lowerExpr);
        Predicate expected = mock(Predicate.class);
        when(cb.like(lowerExpr, "%suf"))
                .thenReturn(expected);

        PredicateBuilder b = new EndsWithPredicateBuilder();
        Predicate p = b.build(cb, path, "SuF");
        assertSame(expected, p);
        verify(cb).like(lowerExpr, "%suf");
    }

    @Test
    @DisplayName("GREATER_OR_EQUAL -> criteriaBuilder.greaterThanOrEqualTo(expr, value)")
    @SuppressWarnings({"rawtypes","unchecked"})
    void greaterOrEqualPredicate() {
        Predicate expected = mock(Predicate.class);
        when(cb.greaterThanOrEqualTo((Expression) path, 10)).thenReturn(expected);

        PredicateBuilder b = new GreaterOrEqualPredicateBuilder();
        Predicate p = b.build(cb, path, 10);
        assertSame(expected, p);
        verify(cb).greaterThanOrEqualTo((Expression) path, 10);
    }

    @Test
    @DisplayName("LESS_OR_EQUAL -> criteriaBuilder.lessThanOrEqualTo(expr, value)")
    @SuppressWarnings({"rawtypes","unchecked"})
    void lessOrEqualPredicate() {
        Predicate expected = mock(Predicate.class);
        when(cb.lessThanOrEqualTo((Expression) path, 5)).thenReturn(expected);

        PredicateBuilder b = new LessOrEqualPredicateBuilder();
        Predicate p = b.build(cb, path, 5);
        assertSame(expected, p);
        verify(cb).lessThanOrEqualTo((Expression) path, 5);
    }

    @Test
    @DisplayName("NOT_IN -> NOT(path IN (...))")
    @SuppressWarnings({"rawtypes","unchecked"})
    void notInPredicate() {
        CriteriaBuilder.In inClause = mock(CriteriaBuilder.In.class);
        when(cb.in(any(Expression.class))).thenReturn(inClause);
        when(inClause.value(any())).thenReturn(inClause);
        Predicate expected = mock(Predicate.class);
        when(cb.not(inClause)).thenReturn(expected);

        List<String> values = Arrays.asList("A", "B", "C");
        PredicateBuilder b = new NotInPredicateBuilder();
        Predicate p = b.build(cb, path, values);
        assertSame(expected, p);
        verify(cb).in(any(Expression.class));
        verify(inClause).value("A");
        verify(inClause).value("B");
        verify(inClause).value("C");
        verify(cb).not(inClause);
    }

    @Test
    @DisplayName("IS_NULL -> criteriaBuilder.isNull(path) when TRUE, else conjunction")
    void isNullPredicate() {
        Predicate expected = mock(Predicate.class);
        when(cb.isNull(path)).thenReturn(expected);
        Predicate conj = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conj);

        PredicateBuilder b = new IsNullPredicateBuilder();
        Predicate pTrue = b.build(cb, path, Boolean.TRUE);
        assertSame(expected, pTrue);
        Predicate pFalse = b.build(cb, path, Boolean.FALSE);
        assertSame(conj, pFalse);
        verify(cb).isNull(path);
        verify(cb).conjunction();
    }

    @Test
    @DisplayName("IS_NOT_NULL -> criteriaBuilder.isNotNull(path) when TRUE, else conjunction")
    void isNotNullPredicate() {
        Predicate expected = mock(Predicate.class);
        when(cb.isNotNull(path)).thenReturn(expected);
        Predicate conj = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(conj);

        PredicateBuilder b = new IsNotNullPredicateBuilder();
        Predicate pTrue = b.build(cb, path, Boolean.TRUE);
        assertSame(expected, pTrue);
        Predicate pFalse = b.build(cb, path, Boolean.FALSE);
        assertSame(conj, pFalse);
        verify(cb).isNotNull(path);
        verify(cb).conjunction();
    }

    // ---------------- Negative cases ----------------

    @Test
    @DisplayName("NOT_LIKE with non-String should throw")
    void notLikeInvalidType() {
        PredicateBuilder b = new NotLikePredicateBuilder();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.build(cb, path, 123));
    }

    @Test
    @DisplayName("STARTS_WITH with non-String should throw")
    void startsWithInvalidType() {
        PredicateBuilder b = new StartsWithPredicateBuilder();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.build(cb, path, 123));
    }

    @Test
    @DisplayName("ENDS_WITH with non-String should throw")
    void endsWithInvalidType() {
        PredicateBuilder b = new EndsWithPredicateBuilder();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.build(cb, path, 123));
    }

    @Test
    @DisplayName("NOT_IN with non-List should throw")
    void notInInvalidType() {
        PredicateBuilder b = new NotInPredicateBuilder();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.build(cb, path, "not-a-list"));
    }

    @Test
    @DisplayName("GTE with non-Comparable should throw")
    void gteInvalidType() {
        PredicateBuilder b = new GreaterOrEqualPredicateBuilder();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.build(cb, path, new Object()));
    }

    @Test
    @DisplayName("LTE with non-Comparable should throw")
    void lteInvalidType() {
        PredicateBuilder b = new LessOrEqualPredicateBuilder();
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> b.build(cb, path, new Object()));
    }
}
