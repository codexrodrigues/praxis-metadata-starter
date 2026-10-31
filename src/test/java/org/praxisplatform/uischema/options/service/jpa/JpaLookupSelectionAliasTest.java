package org.praxisplatform.uischema.options.service.jpa;

import jakarta.persistence.Embeddable;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.id.CompositeResourceId;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A shared Criteria Path must not have its identity alias overwritten by rich lookup fields. */
@SuppressWarnings({"unchecked", "rawtypes"})
class JpaLookupSelectionAliasTest {
    @Embeddable
    record SampleCompositeId(Long numfunc, Integer numvinc) implements CompositeResourceId {}

    @Test
    void sameScalarPathGetsIndependentCodeDescriptionAndDisplaySelections() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root root = mock(Root.class);
        Path path = mock(Path.class);
        when(root.get("code")).thenReturn(path);
        when(path.getJavaType()).thenReturn(String.class);
        when(cb.coalesce(path, path)).thenAnswer(call -> {
            Expression expression = mock(Expression.class);
            when(expression.alias(anyString())).thenReturn(expression);
            return expression;
        });
        List<Selection<?>> selections = new ArrayList<>();
        List<Path<?>> paths = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        var executor = new JpaOptionSourceQueryExecutor();
        for (String alias : List.of("lookupCode", "lookupDescription0", "lookupRichField0"))
            ReflectionTestUtils.invokeMethod(executor, "addSelection", cb, selections, paths, aliases, root, "code", alias);
        assertThat(selections).hasSize(3);
        assertThat(new HashSet<>(selections)).hasSize(3);
        assertThat(aliases).containsExactly("lookupCode", "lookupDescription0", "lookupRichField0");
        verify(path, never()).alias(anyString());
        ReflectionTestUtils.invokeMethod(executor, "addSelection", cb, selections, paths, aliases, root, "code", "lookupCode");
        assertThat(selections).hasSize(3);
    }

    @Test
    void compositeIdPathExpandsIntoScalarComponentSelectionsAndTupleWireString() {
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root root = mock(Root.class);
        Path compositePath = mock(Path.class);
        Path numfuncPath = mock(Path.class);
        Path numvincPath = mock(Path.class);
        when(root.get("id")).thenReturn(compositePath);
        when(compositePath.getJavaType()).thenReturn(SampleCompositeId.class);
        when(compositePath.get("numfunc")).thenReturn(numfuncPath);
        when(compositePath.get("numvinc")).thenReturn(numvincPath);
        when(cb.coalesce(any(Expression.class), any(Expression.class))).thenAnswer(call -> {
            Expression expression = mock(Expression.class);
            when(expression.alias(anyString())).thenReturn(expression);
            return expression;
        });

        List<Selection<?>> selections = new ArrayList<>();
        List<Path<?>> paths = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        var executor = new JpaOptionSourceQueryExecutor();
        ReflectionTestUtils.invokeMethod(executor, "addSelection", cb, selections, paths, aliases, root, "id", "lookupCode");

        assertThat(selections).hasSize(2);
        assertThat(aliases).containsExactly("lookupCode__0", "lookupCode__1", "lookupCode");
        verify(cb, never()).coalesce(compositePath, compositePath);
        verify(cb).coalesce(numfuncPath, numfuncPath);
        verify(cb).coalesce(numvincPath, numvincPath);

        Tuple tuple = mock(Tuple.class);
        when(tuple.get("lookupCode")).thenThrow(new IllegalArgumentException("not present"));
        when(tuple.get("lookupCode__0")).thenReturn(1001L);
        when(tuple.get("lookupCode__1")).thenReturn(1);
        when(tuple.get("lookupCode__2")).thenThrow(new IllegalArgumentException("end"));

        Object value = ReflectionTestUtils.invokeMethod(executor, "tupleValue", tuple, "lookupCode");
        assertThat(value).isEqualTo("1001:1");
    }
}
