package org.praxisplatform.uischema.customfields;

import org.junit.jupiter.api.Test;
import jakarta.persistence.criteria.*;
import org.hibernate.query.criteria.JpaExpression;
import org.praxisplatform.uischema.filter.annotation.Filterable.FilterOperation;
import org.praxisplatform.uischema.rest.exceptionhandler.exception.InvalidFilterPayloadException;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class CustomFieldJpaCriteriaSupportTest {
    static class Entity { @CustomFields Map<String,Object> customFields; }
    @Test void deniesUnknownAndNonFilterableKeysBeforeQueryExecution() {
        Root root = mock(Root.class); when(root.getJavaType()).thenReturn(Entity.class);
        assertThrows(InvalidFilterPayloadException.class, () -> CustomFieldJpaCriteriaSupport.predicates(Map.of("unknown", 1), List.of(), root, mock(CriteriaBuilder.class)));
        var d = new CustomFieldDescriptor("secret", null, null, CustomFieldDataType.STRING, null, null, 0, false, false, false, false, false,
                null,null,null,null,null,null,null,null,null,null,null,null,null);
        assertThrows(InvalidFilterPayloadException.class, () -> CustomFieldJpaCriteriaSupport.predicates(Map.of("secret", "value"), List.of(d), root, mock(CriteriaBuilder.class)));
    }
    @Test void numericComparisonsUseRealSqlCastRatherThanLexicographicText() {
        Root root = mock(Root.class); when(root.getJavaType()).thenReturn(Entity.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class); Path column = mock(Path.class); when(root.get("customFields")).thenReturn(column);
        Expression key = mock(Expression.class); when(cb.literal("age")).thenReturn(key);
        JpaExpression text = mock(JpaExpression.class); when(cb.function("jsonb_extract_path_text", String.class, column, key)).thenReturn(text);
        JpaExpression numeric = mock(JpaExpression.class); when(text.cast(BigDecimal.class)).thenReturn(numeric);
        CustomFieldJpaCriteriaSupport.predicates(Map.of("age", 9), List.of(CustomFieldFixtures.field("age", CustomFieldDataType.INTEGER, FilterOperation.GREATER_OR_EQUAL)), root, cb);
        verify(text).cast(BigDecimal.class);
        verify(cb).greaterThanOrEqualTo(numeric, new BigDecimal("9"));
    }
    @Test void emptyCustomMapDoesNotInspectEntityOrBuildSql() {
        Root root = mock(Root.class); CriteriaBuilder cb = mock(CriteriaBuilder.class);
        assertTrue(CustomFieldJpaCriteriaSupport.predicates(Map.of(), List.of(), root, cb).isEmpty());
        verifyNoInteractions(root, cb);
    }

    private static final class Criteria {
        final Root root = mock(Root.class);
        final CriteriaBuilder cb = mock(CriteriaBuilder.class);
        final Path column = mock(Path.class);
        final Expression key = mock(Expression.class);
        final JpaExpression text = mock(JpaExpression.class);
        final JpaExpression typed = mock(JpaExpression.class);
        final org.hibernate.query.criteria.JpaPredicate predicate = mock(org.hibernate.query.criteria.JpaPredicate.class);
        Criteria() {
            when(root.getJavaType()).thenReturn(Entity.class);
            when(root.get("customFields")).thenReturn(column);
            when(cb.literal("field")).thenReturn(key);
            when(cb.function("jsonb_extract_path_text",String.class,column,key)).thenReturn(text);
            when(text.cast(BigDecimal.class)).thenReturn(typed);
            when(text.cast(Boolean.class)).thenReturn(typed);
            when(text.cast(java.time.LocalDate.class)).thenReturn(typed);
            when(text.cast(java.time.OffsetDateTime.class)).thenReturn(typed);
        }
        List<Predicate> apply(CustomFieldDataType type, FilterOperation operation, Object value) {
            Map<String,Object> values = new LinkedHashMap<>(); values.put("field",value);
            return CustomFieldJpaCriteriaSupport.predicates(values,
                    List.of(CustomFieldFixtures.field("field",type,operation)),root,cb);
        }
    }
    @Test void equalUsesBoundLiteralKeyAndTypedScalarValues() {
        var c = new Criteria();
        when(c.cb.equal(c.text,"ab")).thenReturn(c.predicate);
        assertEquals(List.of(c.predicate),c.apply(CustomFieldDataType.STRING,FilterOperation.EQUAL,"ab"));
        verify(c.cb).function("jsonb_extract_path_text",String.class,c.column,c.key);
        c.apply(CustomFieldDataType.INTEGER,FilterOperation.EQUAL,42);
        verify(c.cb).equal(c.typed,new BigDecimal("42"));
        c.apply(CustomFieldDataType.BOOLEAN,FilterOperation.EQUAL,true);
        verify(c.cb).equal(c.typed,true);
        c.apply(CustomFieldDataType.DATE,FilterOperation.EQUAL,"2026-10-27");
        verify(c.cb).equal(c.typed,java.time.LocalDate.of(2026,10,27));
        c.apply(CustomFieldDataType.DATE_TIME,FilterOperation.EQUAL,"2026-10-27T12:00:00Z");
        verify(c.cb).equal(c.typed,java.time.OffsetDateTime.parse("2026-10-27T12:00:00Z"));
    }
    @Test void likeUsesCaseInsensitiveContainsForDeclaredString() {
        var c = new Criteria();
        Expression<String> lowered = mock(Expression.class);
        when(c.cb.lower(c.text)).thenReturn(lowered);
        when(c.cb.like(lowered,"%abc%")).thenReturn(c.predicate);
        assertEquals(List.of(c.predicate),c.apply(CustomFieldDataType.STRING,FilterOperation.LIKE,"AbC"));
        verify(c.cb).like(lowered,"%abc%");
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.INTEGER,FilterOperation.LIKE,42));
    }
    @Test void inNormalizesEachItemAndRejectsScalarMixedOrNullValues() {
        var c = new Criteria();
        var numbers = List.of(new BigDecimal("2"),new BigDecimal("10"));
        when(c.typed.in(numbers)).thenReturn(c.predicate);
        assertEquals(List.of(c.predicate),c.apply(CustomFieldDataType.INTEGER,FilterOperation.IN,List.of(2,10)));
        verify(c.typed).in(numbers);
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.INTEGER,FilterOperation.IN,2));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.INTEGER,FilterOperation.IN,List.of(2,"10")));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.INTEGER,FilterOperation.IN,Arrays.asList(2,null)));
    }
    @Test void lessOrEqualUsesOrderedNumericAndDateExpressions() {
        var c = new Criteria();
        when(c.cb.lessThanOrEqualTo(c.typed,new BigDecimal("10.5"))).thenReturn(c.predicate);
        assertEquals(List.of(c.predicate),c.apply(CustomFieldDataType.DECIMAL,FilterOperation.LESS_OR_EQUAL,new BigDecimal("10.5")));
        verify(c.text).cast(BigDecimal.class);
        var date = java.time.LocalDate.of(2026,10,27);
        c.apply(CustomFieldDataType.DATE,FilterOperation.LESS_OR_EQUAL,date);
        verify(c.cb).lessThanOrEqualTo(c.typed,date);
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.BOOLEAN,FilterOperation.LESS_OR_EQUAL,true));
    }
    @Test void greaterOrEqualRejectsNonOrderedAndMismatchedValues() {
        var c = new Criteria();
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.BOOLEAN,FilterOperation.GREATER_OR_EQUAL,true));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.INTEGER,FilterOperation.GREATER_OR_EQUAL,"10"));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.INTEGER,FilterOperation.GREATER_OR_EQUAL,10.5));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.ARRAY,FilterOperation.LESS_OR_EQUAL,List.of(1)));
    }
    @Test void isNullRequiresBooleanActivationAndDoesNotCastTheValue() {
        var c = new Criteria();
        when(c.cb.isNull(c.text)).thenReturn(c.predicate);
        assertEquals(List.of(c.predicate),c.apply(CustomFieldDataType.INTEGER,FilterOperation.IS_NULL,true));
        assertTrue(c.apply(CustomFieldDataType.INTEGER,FilterOperation.IS_NULL,false).isEmpty());
        assertTrue(c.apply(CustomFieldDataType.INTEGER,FilterOperation.IS_NULL,null).isEmpty());
        verify(c.cb,times(1)).isNull(c.text);
        verify(c.text,never()).cast(any());
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.INTEGER,FilterOperation.IS_NULL,"true"));
    }
    @Test void equalRejectsInvalidEnumDateNumberAndBooleanWithoutCoercion() {
        var c = new Criteria();
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.ENUM,FilterOperation.EQUAL,"outside"));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.DATE,FilterOperation.EQUAL,"2026-02-30"));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.DATE_TIME,FilterOperation.EQUAL,"yesterday"));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.DECIMAL,FilterOperation.EQUAL,Double.NaN));
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.BOOLEAN,FilterOperation.EQUAL,"true"));
    }
    @Test void nullDeclaredValuesAreOmittedButUnknownNullKeysAreStillDenied() {
        var c = new Criteria();
        assertTrue(c.apply(CustomFieldDataType.INTEGER,FilterOperation.EQUAL,null).isEmpty());
        verify(c.cb,never()).function(anyString(),any(),any(Expression.class),any(Expression.class));
        Map<String,Object> unknown = new LinkedHashMap<>(); unknown.put("unknown",null);
        assertThrows(InvalidFilterPayloadException.class,() -> CustomFieldJpaCriteriaSupport.predicates(unknown,List.of(),c.root,c.cb));
    }
    @Test void unsupportedOperationsFailClosed() {
        var c = new Criteria();
        assertThrows(InvalidFilterPayloadException.class,() -> c.apply(CustomFieldDataType.STRING,FilterOperation.NOT_EQUAL,"ab"));
    }
}
