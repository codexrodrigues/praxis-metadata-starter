package org.praxisplatform.uischema.options.service.jpa;

import jakarta.persistence.criteria.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A shared Criteria Path must not have its identity alias overwritten by rich lookup fields. */
@SuppressWarnings({"unchecked", "rawtypes"})
class JpaLookupSelectionAliasTest {
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
}
