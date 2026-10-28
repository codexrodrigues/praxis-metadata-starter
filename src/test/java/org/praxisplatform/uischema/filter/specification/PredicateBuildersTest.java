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

    static class BroadSearchFilterDTO implements org.praxisplatform.uischema.filter.dto.GenericFilterDTO {
        @Filterable(
                operation = Filterable.FilterOperation.LIKE,
                relations = {"fullName", "socialName", "dependents.fullName"}
        )
        String searchTerm;
    }

    @Test
    @DisplayName("GenericSpecificationsBuilder with @Filterable(relations = {...}) builds OR predicate and sets distinct(true)")
    @SuppressWarnings({"rawtypes", "unchecked"})
    void multiRelationsBuildsDisjunctionAndSetsDistinct() {
        jakarta.persistence.criteria.Root root = mock(jakarta.persistence.criteria.Root.class);
        jakarta.persistence.criteria.CriteriaQuery query = mock(jakarta.persistence.criteria.CriteriaQuery.class);
        jakarta.persistence.criteria.Join dependentsJoin = mock(jakarta.persistence.criteria.Join.class);
        Path fullNamePath = mock(Path.class);
        Path socialNamePath = mock(Path.class);
        Path depNamePath = mock(Path.class);
        Expression fullNameStr = mock(Expression.class);
        Expression socialNameStr = mock(Expression.class);
        Expression depNameStr = mock(Expression.class);
        Expression lower1 = mock(Expression.class);
        Expression lower2 = mock(Expression.class);
        Expression lower3 = mock(Expression.class);
        Predicate p1 = mock(Predicate.class);
        Predicate p2 = mock(Predicate.class);
        Predicate p3 = mock(Predicate.class);
        Predicate orPred = mock(Predicate.class);
        Predicate andPred = mock(Predicate.class);

        when(root.get("fullName")).thenReturn(fullNamePath);
        when(root.get("socialName")).thenReturn(socialNamePath);
        when(root.getJoins()).thenReturn(java.util.Set.of());
        when(root.join("dependents", jakarta.persistence.criteria.JoinType.LEFT)).thenReturn(dependentsJoin);
        when(dependentsJoin.get("fullName")).thenReturn(depNamePath);

        when(fullNamePath.as(String.class)).thenReturn(fullNameStr);
        when(socialNamePath.as(String.class)).thenReturn(socialNameStr);
        when(depNamePath.as(String.class)).thenReturn(depNameStr);
        when(cb.lower(fullNameStr)).thenReturn(lower1);
        when(cb.lower(socialNameStr)).thenReturn(lower2);
        when(cb.lower(depNameStr)).thenReturn(lower3);
        when(cb.like(lower1, "%silva%")).thenReturn(p1);
        when(cb.like(lower2, "%silva%")).thenReturn(p2);
        when(cb.like(lower3, "%silva%")).thenReturn(p3);
        when(cb.or(any(Predicate[].class))).thenReturn(orPred);
        when(cb.and(any(Predicate[].class))).thenReturn(andPred);

        BroadSearchFilterDTO filter = new BroadSearchFilterDTO();
        filter.searchTerm = "Silva";

        GenericSpecificationsBuilder<Object> builder = new GenericSpecificationsBuilder<>();
        GenericSpecification<Object> spec = builder.buildSpecification(
                filter,
                org.springframework.data.domain.PageRequest.of(0, 10, org.springframework.data.domain.Sort.by("searchTerm"))
        );
        Predicate built = spec.spec().toPredicate(root, query, cb);

        assertSame(andPred, built);
        verify(query).distinct(true);
        verify(cb).or(any(Predicate[].class));
        org.junit.jupiter.api.Assertions.assertEquals(
                "fullName",
                spec.pageable().getSort().iterator().next().getProperty()
        );
    }
}
