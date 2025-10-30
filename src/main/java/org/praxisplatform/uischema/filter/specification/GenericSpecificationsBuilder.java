package org.praxisplatform.uischema.filter.specification;

import org.praxisplatform.uischema.filter.annotation.Filterable;
import org.praxisplatform.uischema.filter.dto.GenericFilterDTO;
import jakarta.persistence.criteria.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Classe responsável por construir Specifications genéricas para consultas JPA, baseando-se em DTOs de filtro
 * anotados com a anotação {@link Filterable}. Esta classe suporta filtros dinâmicos e geração automática de
 * critérios de consulta.
 *
 * <p>Principais responsabilidades:
 * <ul>
 *   <li>Identificar campos no DTO que possuem a anotação {@link Filterable}.</li>
 *   <li>Resolver caminhos de propriedades, incluindo relacionamentos e atributos básicos.</li>
 *   <li>Construir predicados (restrições de consulta) baseados nas operações especificadas em {@link Filterable}.</li>
 * </ul>
 *
 * @param <E> Tipo da entidade alvo da consulta.
 */
public class GenericSpecificationsBuilder<E> {

    /**
     * Lista de construtores de predicados. Cada implementação de {@link PredicateBuilder} é responsável
     * por criar um tipo específico de predicado (e.g., igual, maior que, menor que, LIKE, IN).
     */
    private final List<PredicateBuilder> predicateBuilders = List.of(
            new EqualPredicateBuilder(),
            new NotEqualPredicateBuilder(),
            new LikePredicateBuilder(),
            new NotLikePredicateBuilder(),
            new StartsWithPredicateBuilder(),
            new EndsWithPredicateBuilder(),
            new GreaterThanPredicateBuilder(),
            new GreaterOrEqualPredicateBuilder(),
            new LessThanPredicateBuilder(),
            new LessOrEqualPredicateBuilder(),
            new InPredicateBuilder(),
            new NotInPredicateBuilder(),
            new BetweenPredicateBuilder(), // BETWEEN
            new BetweenExclusivePredicateBuilder(),
            new NotBetweenPredicateBuilder(),
            new OutsideRangePredicateBuilder(),
            new OnDatePredicateBuilder(),
            new InLastDaysPredicateBuilder(),
            new InNextDaysPredicateBuilder(),
            new SizeEqPredicateBuilder(),
            new SizeGtPredicateBuilder(),
            new SizeLtPredicateBuilder(),
            new IsTruePredicateBuilder(),
            new IsFalsePredicateBuilder(),
            new IsNullPredicateBuilder(),
            new IsNotNullPredicateBuilder()
    );

    /**
     * Método principal que constrói uma Specification com base em um DTO de filtro, além de processar
     * {@link Pageable} quanto aos relacionamentos descritos em {@link Filterable#relation()} e apontados em {@link Sort}.
     *
     * @param filter   DTO que contém os critérios de filtro anotados com {@link Filterable}.
     * @param pageable contém critérios de ordenação que vem da camada REST e orientam a query
     * @return Um GenericSpecification que armazena uma Specification genérica para ser usada em repositórios
     * Spring Data JPA e um Pageable ajustado conforme o DTO de entrada.
     */
    public <FDT extends GenericFilterDTO> GenericSpecification<E> buildSpecification(FDT filter, Pageable pageable) {
        return new GenericSpecification<>(
                processSpecification(filter),
                processPageable(filter, pageable)
        );
    }

    private <FDT extends GenericFilterDTO> Specification<E> processSpecification(FDT filter) {
        return (root, query, criteriaBuilder) -> {
            List<Predicate> predicates = new ArrayList<>();

            List<Field> fields = getAnnotatedFields(filter);

            for (Field field : fields) {
                processField(field, filter, root, criteriaBuilder, predicates);
            }

            return criteriaBuilder.and(predicates.toArray(new Predicate[0]));
        };
    }

    private <FDT extends GenericFilterDTO> Pageable processPageable(FDT filter, Pageable oldPageable) {
        Sort sort = oldPageable.getSort();
        if (!sort.isSorted()) {
            return oldPageable;
        }
        // Spring Data's Sort exposes the orders through the {@code stream()} method.
        // Using {@code get()} would fail to compile on newer versions where the
        // method does not exist. The stream is then mapped to keep the original
        // direction while replacing the property when a relation is configured.
        List<Sort.Order> orderList = sort.stream().map(order -> {
            try {
                Field field = filter.getClass().getDeclaredField(order.getProperty());
                Filterable filterable = field.getAnnotation(Filterable.class);
                if (filterable == null || filterable.relation().isEmpty()) {
                    return order;
                }
                return order.withProperty(filterable.relation());
            } catch (NoSuchFieldException e) {
                return order;
            }
        }).toList();
        return ((PageRequest) oldPageable).withSort(Sort.by(orderList));
    }

    /**
     * Identifica os campos no DTO que possuem a anotação {@link Filterable}.
     *
     * @param filter Instância do DTO de filtro.
     * @return Lista de campos anotados.
     */
    private <FDT extends GenericFilterDTO> List<Field> getAnnotatedFields(FDT filter) {
        List<Field> annotatedFields = new ArrayList<>();
        for (Field field : filter.getClass().getDeclaredFields()) {
            if (field.isAnnotationPresent(Filterable.class)) {
                annotatedFields.add(field);
            }
        }
        return annotatedFields;
    }

    /**
     * Processa um campo anotado com {@link Filterable} para criar um predicado correspondente.
     *
     * <p>Etapas:
     * <ul>
     *   <li>Extrai o valor do campo no DTO.</li>
     *   <li>Resolve o caminho para a propriedade correspondente na entidade.</li>
     *   <li>Identifica o tipo de operação (e.g., EQUAL, LIKE) e cria o predicado apropriado.</li>
     * </ul>
     *
     * @param field           Campo do DTO.
     * @param filter          Instância do DTO de filtro.
     * @param root            Raiz da consulta JPA.
     * @param criteriaBuilder Construtor de critérios JPA.
     * @param predicates      Lista de predicados a ser preenchida.
     */
    private <FDT extends GenericFilterDTO> void processField(Field field, FDT filter, Root<E> root,
                              CriteriaBuilder criteriaBuilder, List<Predicate> predicates) {
        try {
            field.setAccessible(true);
            Object value = field.get(filter);
            if (value != null) {
                Filterable filterable = field.getAnnotation(Filterable.class);
                jakarta.persistence.criteria.Path<?> path;

                // Usa o campo relacionado, se especificado
                String relation = filterable.relation();
                if (!relation.isEmpty()) {
                    path = resolvePath(root, relation);
                } else {
                    path = root.get(field.getName());
                }

                // Adiciona o predicado com base na operação
                addPredicate(predicates, criteriaBuilder, path, value, filterable);
            }
        } catch (IllegalAccessException e) {
            throw new SpecificationBuildException("Erro ao acessar o campo: " + field.getName(), e);
        }
    }


    /**
     * Resolve o caminho para uma propriedade, incluindo múltiplos níveis de relacionamentos (e.g., "tipoSexo.nome").
     *
     * <p>Etapas:
     * <ul>
     *   <li>Navega pelos relacionamentos usando {@link Join} para cada nível intermediário.</li>
     *   <li>Acessa o atributo básico no último nível.</li>
     * </ul>
     *
     * @param root         Raiz da entidade na consulta JPA.
     * @param relationPath Caminho da relação no formato "relacao1.relacao2.atributo".
     * @return O caminho resolvido para a propriedade.
     */
    jakarta.persistence.criteria.Path<?> resolvePath(Root<?> root, String relationPath) {
        String[] relations = relationPath.split("\\."); // Divide o caminho em partes
        jakarta.persistence.criteria.Path<?> path = root;

        for (int i = 0; i < relations.length; i++) {
            String relation = relations[i];
            if (i == relations.length - 1) {
                // Último elemento: acessa diretamente como atributo básico
                path = path.get(relation);
            } else if (path instanceof Root<?>) {
                // Relacionamento no root
                path = ((Root<?>) path).join(relation, JoinType.LEFT);
            } else if (path instanceof From<?, ?>) {
                // Relacionamento em joins
                path = ((From<?, ?>) path).join(relation, JoinType.LEFT);
            } else {
                throw new IllegalArgumentException("Não foi possível resolver o caminho: " + relationPath);
            }
        }

        return path;
    }


    /**
     * Cria e adiciona um predicado à lista com base no tipo de operação definido em {@link Filterable}.
     *
     * @param predicates      Lista de predicados.
     * @param criteriaBuilder Construtor de critérios JPA.
     * @param path            Caminho para a propriedade na entidade.
     * @param value           Valor do filtro.
     * @param filterable      Anotação {@link Filterable} que define a operação.
     */
    private void addPredicate(List<Predicate> predicates, CriteriaBuilder criteriaBuilder,
                              jakarta.persistence.criteria.Path<?> path, Object value, Filterable filterable) {
        Optional<PredicateBuilder> builder = predicateBuilders.stream()
                .filter(b -> b.supports(filterable.operation()))
                .findFirst();

        builder.ifPresent(predicateBuilder -> predicates.add(
                predicateBuilder.build(criteriaBuilder, path, value))
        );
    }
}

// PredicateBuilder interface
interface PredicateBuilder {
    boolean supports(Filterable.FilterOperation operation);

    Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value);
}

// Implementation examples
class EqualPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.EQUAL;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        return criteriaBuilder.equal(path, value);
    }
}

class NotEqualPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.NOT_EQUAL;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        return criteriaBuilder.notEqual(path, value);
    }
}

class LikePredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.LIKE;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (path == null) {
            throw new IllegalArgumentException("O caminho fornecido não é uma expressão válida.");
        }

        if (value instanceof String) {
            jakarta.persistence.criteria.Expression<String> stringExpression =
                    path.as(String.class); // Usa o método `as` para criar um tipo seguro.
            return criteriaBuilder.like(
                    criteriaBuilder.lower(stringExpression),
                    "%" + ((String) value).toLowerCase() + "%"
            );
        }
        throw new IllegalArgumentException("LIKE operation requires a String value.");
    }
}

class NotLikePredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.NOT_LIKE;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof String) {
            jakarta.persistence.criteria.Expression<String> stringExpression = path.as(String.class);
            return criteriaBuilder.not(
                    criteriaBuilder.like(
                            criteriaBuilder.lower(stringExpression),
                            "%" + ((String) value).toLowerCase() + "%"
                    )
            );
        }
        throw new IllegalArgumentException("NOT_LIKE operation requires a String value.");
    }
}

class StartsWithPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.STARTS_WITH;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof String) {
            jakarta.persistence.criteria.Expression<String> stringExpression = path.as(String.class);
            return criteriaBuilder.like(
                    criteriaBuilder.lower(stringExpression),
                    ((String) value).toLowerCase() + "%"
            );
        }
        throw new IllegalArgumentException("STARTS_WITH operation requires a String value.");
    }
}

class EndsWithPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.ENDS_WITH;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof String) {
            jakarta.persistence.criteria.Expression<String> stringExpression = path.as(String.class);
            return criteriaBuilder.like(
                    criteriaBuilder.lower(stringExpression),
                    "%" + ((String) value).toLowerCase()
            );
        }
        throw new IllegalArgumentException("ENDS_WITH operation requires a String value.");
    }
}


class GreaterThanPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.GREATER_THAN;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Comparable) {
            return criteriaBuilder.greaterThan(
                    (jakarta.persistence.criteria.Expression<? extends Comparable>) path,
                    (Comparable) value
            );
        }
        throw new IllegalArgumentException("GREATER_THAN operation requires a Comparable value.");
    }
}

class GreaterOrEqualPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.GREATER_OR_EQUAL;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Comparable) {
            return criteriaBuilder.greaterThanOrEqualTo(
                    (jakarta.persistence.criteria.Expression<? extends Comparable>) path,
                    (Comparable) value
            );
        }
        throw new IllegalArgumentException("GREATER_OR_EQUAL operation requires a Comparable value.");
    }
}

class LessThanPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.LESS_THAN;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Comparable) {
            return criteriaBuilder.lessThan(
                    (jakarta.persistence.criteria.Expression<? extends Comparable>) path,
                    (Comparable) value
            );
        }
        throw new IllegalArgumentException("LESS_THAN operation requires a Comparable value.");
    }
}

class LessOrEqualPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.LESS_OR_EQUAL;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Comparable) {
            return criteriaBuilder.lessThanOrEqualTo(
                    (jakarta.persistence.criteria.Expression<? extends Comparable>) path,
                    (Comparable) value
            );
        }
        throw new IllegalArgumentException("LESS_OR_EQUAL operation requires a Comparable value.");
    }
}

class InPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.IN;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof List) {
            CriteriaBuilder.In<Object> inClause = criteriaBuilder.in(path);
            for (Object val : (List<?>) value) {
                inClause.value(val);
            }
            return inClause;
        }
        throw new IllegalArgumentException("IN operation requires a List value.");
    }
}

class NotInPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.NOT_IN;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof java.util.List<?> list) {
            CriteriaBuilder.In<Object> inClause = criteriaBuilder.in(path);
            for (Object val : list) {
                inClause.value(val);
            }
            return criteriaBuilder.not(inClause);
        }
        throw new IllegalArgumentException("NOT_IN operation requires a List value.");
    }
}


class BetweenPredicateBuilder implements PredicateBuilder {

    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.BETWEEN;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof List<?> values && values.size() == 2) {
            Object start = values.get(0);
            Object end = values.get(1);
            jakarta.persistence.criteria.Expression<? extends Comparable> expression;
            if (start instanceof LocalDate && end instanceof LocalDate) {
                expression = path.as(Instant.class);
                start = ((LocalDate) values.get(0)).atStartOfDay().toInstant(ZoneOffset.UTC);
                end = ((LocalDate) values.get(1)).atStartOfDay().toInstant(ZoneOffset.UTC);
            } else {
                // Converte o caminho para o tipo correspondente ao valor inicial
                expression = path.as((Class<Comparable>) start.getClass());
            }
            return criteriaBuilder.between(expression, (Comparable) start, (Comparable) end);
        }

        throw new IllegalArgumentException("BETWEEN operation requires a list of exactly two values (start and end).");
    }
}

class BetweenExclusivePredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.BETWEEN_EXCLUSIVE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof List<?> values && values.size() == 2) {
            Object start = values.get(0);
            Object end = values.get(1);
            jakarta.persistence.criteria.Expression<? extends Comparable> expression;
            if (start instanceof LocalDate && end instanceof LocalDate) {
                expression = path.as(Instant.class);
                start = ((LocalDate) start).atStartOfDay().toInstant(ZoneOffset.UTC);
                end = ((LocalDate) end).atStartOfDay().toInstant(ZoneOffset.UTC);
            } else {
                expression = path.as((Class<Comparable>) start.getClass());
            }
            Predicate gt = criteriaBuilder.greaterThan(expression, (Comparable) start);
            Predicate lt = criteriaBuilder.lessThan(expression, (Comparable) end);
            return criteriaBuilder.and(gt, lt);
        }
        throw new IllegalArgumentException("BETWEEN_EXCLUSIVE requires a list of exactly two values.");
    }
}

class NotBetweenPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.NOT_BETWEEN;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof List<?> values && values.size() == 2) {
            Object start = values.get(0);
            Object end = values.get(1);
            jakarta.persistence.criteria.Expression<? extends Comparable> expression;
            if (start instanceof LocalDate && end instanceof LocalDate) {
                expression = path.as(Instant.class);
                start = ((LocalDate) start).atStartOfDay().toInstant(ZoneOffset.UTC);
                end = ((LocalDate) end).atStartOfDay().toInstant(ZoneOffset.UTC);
            } else {
                expression = path.as((Class<Comparable>) start.getClass());
            }
            Predicate between = criteriaBuilder.between(expression, (Comparable) start, (Comparable) end);
            return criteriaBuilder.not(between);
        }
        throw new IllegalArgumentException("NOT_BETWEEN requires a list of exactly two values.");
    }
}

class OutsideRangePredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.OUTSIDE_RANGE;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof List<?> values && values.size() == 2) {
            Object min = values.get(0);
            Object max = values.get(1);
            jakarta.persistence.criteria.Expression<? extends Comparable> expression;
            if (min instanceof LocalDate && max instanceof LocalDate) {
                expression = path.as(Instant.class);
                min = ((LocalDate) min).atStartOfDay().toInstant(ZoneOffset.UTC);
                max = ((LocalDate) max).atStartOfDay().toInstant(ZoneOffset.UTC);
            } else {
                expression = path.as((Class<Comparable>) min.getClass());
            }
            Predicate lt = criteriaBuilder.lessThan(expression, (Comparable) min);
            Predicate gt = criteriaBuilder.greaterThan(expression, (Comparable) max);
            return criteriaBuilder.or(lt, gt);
        }
        throw new IllegalArgumentException("OUTSIDE_RANGE requires a list of exactly two values.");
    }
}

class OnDatePredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.ON_DATE;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof LocalDate date) {
            Instant start = date.atStartOfDay().toInstant(ZoneOffset.UTC);
            Instant end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
            jakarta.persistence.criteria.Expression<? extends Comparable> expression = path.as(Instant.class);
            return criteriaBuilder.between(expression, (Comparable) start, (Comparable) end);
        }
        throw new IllegalArgumentException("ON_DATE requires a LocalDate value.");
    }
}

class InLastDaysPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.IN_LAST_DAYS;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Number n) {
            long days = n.longValue();
            Instant now = Instant.now();
            Instant start = now.minusSeconds(days * 24 * 60 * 60);
            jakarta.persistence.criteria.Expression<? extends Comparable> expression = path.as(Instant.class);
            return criteriaBuilder.between(expression, (Comparable) start, (Comparable) now);
        }
        throw new IllegalArgumentException("IN_LAST_DAYS requires a numeric value (days).");
    }
}

class InNextDaysPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.IN_NEXT_DAYS;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Number n) {
            long days = n.longValue();
            Instant now = Instant.now();
            Instant end = now.plusSeconds(days * 24 * 60 * 60);
            jakarta.persistence.criteria.Expression<? extends Comparable> expression = path.as(Instant.class);
            return criteriaBuilder.between(expression, (Comparable) now, (Comparable) end);
        }
        throw new IllegalArgumentException("IN_NEXT_DAYS requires a numeric value (days).");
    }
}

class SizeEqPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.SIZE_EQ;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Number n) {
            if (!java.util.Collection.class.isAssignableFrom(path.getJavaType())) {
                throw new IllegalArgumentException("SIZE_EQ requires a collection relation. Use @Filterable(relation=...) pointing to a collection attribute.");
            }
            @SuppressWarnings("unchecked")
            jakarta.persistence.criteria.Expression<java.util.Collection<?>> collExpr = (jakarta.persistence.criteria.Expression<java.util.Collection<?>>) path;
            jakarta.persistence.criteria.Expression<Integer> sizeExpr = criteriaBuilder.size(collExpr);
            return criteriaBuilder.equal(sizeExpr, n.intValue());
        }
        throw new IllegalArgumentException("SIZE_EQ requires an Integer value.");
    }
}

class SizeGtPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.SIZE_GT;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Number n) {
            if (!java.util.Collection.class.isAssignableFrom(path.getJavaType())) {
                throw new IllegalArgumentException("SIZE_GT requires a collection relation. Use @Filterable(relation=...) pointing to a collection attribute.");
            }
            @SuppressWarnings("unchecked")
            jakarta.persistence.criteria.Expression<java.util.Collection<?>> collExpr = (jakarta.persistence.criteria.Expression<java.util.Collection<?>>) path;
            jakarta.persistence.criteria.Expression<Integer> sizeExpr = criteriaBuilder.size(collExpr);
            return criteriaBuilder.gt(sizeExpr, n.intValue());
        }
        throw new IllegalArgumentException("SIZE_GT requires an Integer value.");
    }
}

class SizeLtPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.SIZE_LT;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (value instanceof Number n) {
            if (!java.util.Collection.class.isAssignableFrom(path.getJavaType())) {
                throw new IllegalArgumentException("SIZE_LT requires a collection relation. Use @Filterable(relation=...) pointing to a collection attribute.");
            }
            @SuppressWarnings("unchecked")
            jakarta.persistence.criteria.Expression<java.util.Collection<?>> collExpr = (jakarta.persistence.criteria.Expression<java.util.Collection<?>>) path;
            jakarta.persistence.criteria.Expression<Integer> sizeExpr = criteriaBuilder.size(collExpr);
            return criteriaBuilder.lt(sizeExpr, n.intValue());
        }
        throw new IllegalArgumentException("SIZE_LT requires an Integer value.");
    }
}

class IsTruePredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.IS_TRUE;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        jakarta.persistence.criteria.Expression<Boolean> boolExpr = path.as(Boolean.class);
        return criteriaBuilder.isTrue(boolExpr);
    }
}

class IsFalsePredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.IS_FALSE;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        jakarta.persistence.criteria.Expression<Boolean> boolExpr = path.as(Boolean.class);
        return criteriaBuilder.isFalse(boolExpr);
    }
}

class IsNullPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.IS_NULL;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (Boolean.TRUE.equals(value)) {
            return criteriaBuilder.isNull(path);
        }
        // não adiciona predicado quando null/false → sem efeito
        return criteriaBuilder.conjunction();
    }
}

class IsNotNullPredicateBuilder implements PredicateBuilder {
    @Override
    public boolean supports(Filterable.FilterOperation operation) {
        return operation == Filterable.FilterOperation.IS_NOT_NULL;
    }

    @Override
    public Predicate build(CriteriaBuilder criteriaBuilder, jakarta.persistence.criteria.Path<?> path, Object value) {
        if (Boolean.TRUE.equals(value)) {
            return criteriaBuilder.isNotNull(path);
        }
        // não adiciona predicado quando null/false → sem efeito
        return criteriaBuilder.conjunction();
    }
}


// Exception class
class SpecificationBuildException extends RuntimeException {
    public SpecificationBuildException(String message, Throwable cause) {
        super(message, cause);
    }
}
