package org.praxisplatform.uischema.options.service.jpa;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.Test;
import org.praxisplatform.uischema.options.EntityLookupDescriptor;
import org.praxisplatform.uischema.options.LookupFilterDefinition;
import org.praxisplatform.uischema.options.LookupFilterRequest;
import org.praxisplatform.uischema.options.LookupFilteringDescriptor;
import org.praxisplatform.uischema.options.LookupSearchStrategyDefinition;
import org.praxisplatform.uischema.options.OptionSourceDescriptor;
import org.praxisplatform.uischema.options.OptionSourceExecutionMode;
import org.praxisplatform.uischema.options.OptionSourcePolicy;
import org.praxisplatform.uischema.options.OptionSourceType;
import org.praxisplatform.uischema.options.service.OptionSourceExecutionRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings({"unchecked", "rawtypes"})
class JpaOptionSourceQueryExecutorStrategyAndFilterTest {

    static class DummyEntity {
        Long id;
        String cnpjCpf;
        String nome;
    }

    @Test
    void jpaProviderForwardsSearchStrategyToElevenArgExecutor() {
        OptionSourceDescriptor descriptor = descriptorWithDocumentSearch();
        JpaOptionSourceQueryExecutor delegate = mock(JpaOptionSourceQueryExecutor.class);
        EntityManager entityManager = mock(EntityManager.class);
        when(delegate.filterOptions(
                eq(entityManager),
                eq(DummyEntity.class),
                eq(null),
                eq(null),
                eq(descriptor),
                eq("12345678000190"),
                eq("document"),
                eq(List.of()),
                eq(null),
                eq(PageRequest.of(0, 10)),
                eq(List.of())
        )).thenReturn(Page.empty());

        JpaOptionSourceProvider provider = new JpaOptionSourceProvider(delegate);
        OptionSourceExecutionRequest<DummyEntity> request = new OptionSourceExecutionRequest<>(
                new JpaOptionSourceHostContext<>(entityManager, DummyEntity.class, null),
                null,
                descriptor,
                "12.345.678/0001-90",
                "document",
                List.of(),
                null,
                PageRequest.of(0, 10),
                List.of(),
                List.of(),
                null
        );

        assertNotNull(provider.filter(request));
        verify(delegate).filterOptions(
                eq(entityManager),
                eq(DummyEntity.class),
                eq(null),
                eq(null),
                eq(descriptor),
                eq("12345678000190"),
                eq("document"),
                eq(List.of()),
                eq(null),
                eq(PageRequest.of(0, 10)),
                eq(List.of())
        );
    }

    @Test
    void normalizedDocumentSearchStripsVisualSeparatorsInJpaCriteria() {
        OptionSourceDescriptor descriptor = descriptorWithDocumentSearch();
        EntityManager entityManager = mock(EntityManager.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<Tuple> tupleQuery = mock(CriteriaQuery.class);
        CriteriaQuery<Long> countQuery = mock(CriteriaQuery.class);
        Root<DummyEntity> root = mock(Root.class);
        Path idPath = mock(Path.class);
        Path docPath = mock(Path.class);
        Expression<String> loweredExpr = mock(Expression.class);
        Expression<String> replacedExpr = mock(Expression.class);
        Predicate predicate = mock(Predicate.class);
        Order order = mock(Order.class);
        TypedQuery<Tuple> typedTupleQuery = mock(TypedQuery.class);
        TypedQuery<Long> typedCountQuery = mock(TypedQuery.class);

        when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        when(cb.createTupleQuery()).thenReturn(tupleQuery);
        when(cb.createQuery(Long.class)).thenReturn(countQuery);
        when(tupleQuery.from(DummyEntity.class)).thenReturn(root);
        when(countQuery.from(DummyEntity.class)).thenReturn(root);
        when(root.get("id")).thenReturn(idPath);
        when(root.get("cnpjCpf")).thenReturn(docPath);
        when(idPath.getJavaType()).thenReturn(Long.class);
        when(docPath.getJavaType()).thenReturn(String.class);
        when(docPath.as(String.class)).thenReturn(loweredExpr);
        when(cb.lower(any(Expression.class))).thenReturn(loweredExpr);
        when(cb.literal(anyString())).thenAnswer(inv -> mock(Expression.class));
        when(cb.function(eq("replace"), eq(String.class), any(), any(), any())).thenReturn(replacedExpr);
        when(cb.isNotNull(any())).thenReturn(predicate);
        when(cb.like(any(Expression.class), anyString())).thenReturn(predicate);
        when(cb.or(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
        when(cb.or(any(Predicate[].class))).thenReturn(predicate);
        when(cb.and(any(Predicate.class), any(Predicate.class))).thenReturn(predicate);
        when(cb.asc(any())).thenReturn(order);
        when(cb.coalesce(any(Expression.class), any(Expression.class))).thenAnswer(inv -> {
            Expression expr = mock(Expression.class);
            when(expr.alias(anyString())).thenReturn(expr);
            return expr;
        });
        when(cb.countDistinct(any())).thenReturn(mock(Expression.class));
        when(tupleQuery.distinct(any(Boolean.class))).thenReturn(tupleQuery);
        when(tupleQuery.orderBy(any(List.class))).thenReturn(tupleQuery);
        when(tupleQuery.where(any(Predicate.class))).thenReturn(tupleQuery);
        when(countQuery.where(any(Predicate.class))).thenReturn(countQuery);
        when(countQuery.select(any())).thenReturn(countQuery);
        when(entityManager.createQuery(tupleQuery)).thenReturn(typedTupleQuery);
        when(entityManager.createQuery(countQuery)).thenReturn(typedCountQuery);
        when(typedTupleQuery.setFirstResult(anyInt())).thenReturn(typedTupleQuery);
        when(typedTupleQuery.setMaxResults(anyInt())).thenReturn(typedTupleQuery);
        when(typedTupleQuery.getResultList()).thenReturn(List.of());
        when(typedCountQuery.getSingleResult()).thenReturn(0L);

        JpaOptionSourceQueryExecutor executor = new JpaOptionSourceQueryExecutor();
        Page<org.praxisplatform.uischema.dto.OptionDTO<Object>> page = executor.filterOptions(
                entityManager,
                DummyEntity.class,
                null,
                null,
                descriptor,
                "12.345.678/0001-90",
                "document",
                List.of(),
                null,
                PageRequest.of(0, 10),
                List.of()
        );

        assertNotNull(page);
        verify(cb, atLeastOnce()).literal(".");
        verify(cb, atLeastOnce()).literal("-");
        verify(cb, atLeastOnce()).literal("/");
        verify(cb, atLeastOnce()).literal(" ");
        verify(cb, atLeastOnce()).like(any(Expression.class), eq("%12345678000190%"));
    }

    @Test
    void unresolvedStructuredFilterFieldFailsClosed() {
        OptionSourceDescriptor descriptor = new OptionSourceDescriptor(
                "organs",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/organs",
                null,
                "id",
                "nome",
                "id",
                List.of(),
                Map.of(),
                OptionSourcePolicy.defaults(),
                new EntityLookupDescriptor("organs", "id", List.of(), null, null, null, List.of(), Map.of(), null, null, null),
                new LookupFilteringDescriptor(
                        List.of(new LookupFilterDefinition("campoTransienteInexistente", "Campo", "text", List.of("equals"), "equals", null, false, false)),
                        Map.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of()
                ),
                OptionSourceExecutionMode.JPA
        );

        EntityManager entityManager = mock(EntityManager.class);
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        CriteriaQuery<Tuple> tupleQuery = mock(CriteriaQuery.class);
        Root<DummyEntity> root = mock(Root.class);
        Path idPath = mock(Path.class);
        Path nomePath = mock(Path.class);
        when(entityManager.getCriteriaBuilder()).thenReturn(cb);
        when(cb.createTupleQuery()).thenReturn(tupleQuery);
        when(tupleQuery.from(DummyEntity.class)).thenReturn(root);
        when(root.get("id")).thenReturn(idPath);
        when(root.get("nome")).thenReturn(nomePath);
        when(root.get("campoTransienteInexistente"))
                .thenThrow(new IllegalArgumentException("Unable to locate Attribute with the given name [campoTransienteInexistente]"));
        when(cb.isNotNull(any())).thenReturn(mock(Predicate.class));

        JpaOptionSourceQueryExecutor executor = new JpaOptionSourceQueryExecutor();
        assertThrows(IllegalArgumentException.class, () -> executor.filterOptions(
                entityManager,
                DummyEntity.class,
                null,
                null,
                descriptor,
                null,
                null,
                List.of(new LookupFilterRequest("campoTransienteInexistente", "equals", "ABC")),
                null,
                PageRequest.of(0, 10),
                List.of()
        ));
    }

    private static OptionSourceDescriptor descriptorWithDocumentSearch() {
        return new OptionSourceDescriptor(
                "organs",
                OptionSourceType.RESOURCE_ENTITY,
                "/api/organs",
                null,
                "cnpjCpf",
                "cnpjCpf",
                "id",
                List.of(),
                Map.of(),
                new OptionSourcePolicy(false, true, "contains", 1, 20, 50, true, false, "label"),
                new EntityLookupDescriptor("organs", "id", List.of(), null, null, null, List.of("cnpjCpf"), Map.of(), null, null, null),
                new LookupFilteringDescriptor(
                        List.of(),
                        Map.of(),
                        List.of(),
                        null,
                        List.of(),
                        null,
                        List.of(new LookupSearchStrategyDefinition("document", "normalized-document", 11))
                ),
                OptionSourceExecutionMode.JPA
        );
    }
}
