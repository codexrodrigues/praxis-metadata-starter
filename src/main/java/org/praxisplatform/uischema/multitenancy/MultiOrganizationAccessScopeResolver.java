package org.praxisplatform.uischema.multitenancy;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.praxisplatform.uischema.id.JpaCriteriaPathSupport;
import org.praxisplatform.uischema.service.base.ResourceFilterAccessScope;
import org.springframework.data.jpa.domain.Specification;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Resolvedor reutilizavel de {@link ResourceFilterAccessScope} para isolamentos multi-organizacao.
 *
 * <p>Suporta os quatro padroes corporativos principais com semantica fail-closed quando o conjunto
 * de organizacoes autorizadas esta vazio:</p>
 * <ul>
 *   <li>campo escalar direto ({@code orgId IN ...}),</li>
 *   <li>campo escalar anulavel para registros globais ou organizacionais ({@code orgId IS NULL OR orgId IN ...}),</li>
 *   <li>colecao {@code @ElementCollection} ou associacao multivalorada,</li>
 *   <li>subquery correlacionada em entidade pai.</li>
 * </ul>
 */
public final class MultiOrganizationAccessScopeResolver {

    public <E> ResourceFilterAccessScope<E> scalarField(
            String organizationFieldPath,
            Collection<?> allowedOrganizations
    ) {
        String path = requireText(organizationFieldPath, "organizationFieldPath");
        List<?> allowed = normalizeAllowed(allowedOrganizations);
        if (allowed.isEmpty()) {
            return ResourceFilterAccessScope.denied();
        }
        Specification<E> spec = (root, query, cb) -> inPredicate(JpaCriteriaPathSupport.resolvePath(root, path, true), allowed);
        return ResourceFilterAccessScope.restricted(spec);
    }

    public <E> ResourceFilterAccessScope<E> nullableOrIn(
            String organizationFieldPath,
            Collection<?> allowedOrganizations
    ) {
        return nullableOrIn(organizationFieldPath, allowedOrganizations, false);
    }

    public <E> ResourceFilterAccessScope<E> nullableOrIn(
            String organizationFieldPath,
            Collection<?> allowedOrganizations,
            boolean allowGlobalWhenAllowedEmpty
    ) {
        String path = requireText(organizationFieldPath, "organizationFieldPath");
        List<?> allowed = normalizeAllowed(allowedOrganizations);
        if (allowed.isEmpty() && !allowGlobalWhenAllowedEmpty) {
            return ResourceFilterAccessScope.denied();
        }
        Specification<E> spec = (root, query, cb) -> {
            Path<?> target = JpaCriteriaPathSupport.resolvePath(root, path, true);
            Predicate isNull = cb.isNull(target);
            if (allowed.isEmpty()) {
                return isNull;
            }
            return cb.or(isNull, inPredicate(target, allowed));
        };
        return ResourceFilterAccessScope.restricted(spec);
    }

    public <E> ResourceFilterAccessScope<E> elementCollection(
            String collectionFieldPath,
            Collection<?> allowedOrganizations
    ) {
        return elementCollection(collectionFieldPath, null, allowedOrganizations);
    }

    public <E> ResourceFilterAccessScope<E> elementCollection(
            String collectionFieldPath,
            String nestedOrganizationProperty,
            Collection<?> allowedOrganizations
    ) {
        String path = requireText(collectionFieldPath, "collectionFieldPath");
        List<?> allowed = normalizeAllowed(allowedOrganizations);
        if (allowed.isEmpty()) {
            return ResourceFilterAccessScope.denied();
        }
        Specification<E> spec = (root, query, cb) -> {
            if (query != null && query.getResultType() != Long.class && query.getResultType() != long.class) {
                query.distinct(true);
            }
            Join<?, ?> join = joinPath(root, path);
            Path<?> orgPath = (nestedOrganizationProperty == null || nestedOrganizationProperty.isBlank())
                    ? join
                    : resolveNestedPath(join, nestedOrganizationProperty.trim());
            return orgPath.in(allowed);
        };
        return ResourceFilterAccessScope.restricted(spec);
    }

    public <E, P> ResourceFilterAccessScope<E> parentSubquery(
            String rootForeignKeyOrParentIdPath,
            Class<P> parentEntityClass,
            String parentKeyField,
            String parentOrganizationFieldPath,
            Collection<?> allowedOrganizations
    ) {
        String rootFk = requireText(rootForeignKeyOrParentIdPath, "rootForeignKeyOrParentIdPath");
        Objects.requireNonNull(parentEntityClass, "parentEntityClass must not be null");
        String parentKey = requireText(parentKeyField, "parentKeyField");
        String parentOrgPath = requireText(parentOrganizationFieldPath, "parentOrganizationFieldPath");
        List<?> allowed = normalizeAllowed(allowedOrganizations);
        if (allowed.isEmpty()) {
            return ResourceFilterAccessScope.denied();
        }
        Specification<E> spec = (root, query, cb) -> {
            Subquery<Integer> subquery = query.subquery(Integer.class);
            Root<P> parentRoot = subquery.from(parentEntityClass);
            Path<?> childKeyExpr = JpaCriteriaPathSupport.resolvePath(root, rootFk, true);
            Path<?> parentKeyExpr = JpaCriteriaPathSupport.resolvePath(parentRoot, parentKey, true);
            Path<?> parentOrgExpr = JpaCriteriaPathSupport.resolvePath(parentRoot, parentOrgPath, true);
            subquery.select(cb.literal(1))
                    .where(
                            cb.equal(parentKeyExpr, childKeyExpr),
                            inPredicate(parentOrgExpr, allowed)
                    );
            return cb.exists(subquery);
        };
        return ResourceFilterAccessScope.restricted(spec);
    }

    private static Predicate inPredicate(Path<?> target, List<?> allowed) {
        if (allowed.size() == 1) {
            return target.in(allowed.getFirst());
        }
        return target.in(allowed);
    }

    private static Join<?, ?> joinPath(Root<?> root, String collectionPath) {
        String[] segments = collectionPath.trim().split("\\.");
        Join<?, ?> current = root.join(segments[0]);
        for (int i = 1; i < segments.length; i++) {
            current = current.join(segments[i]);
        }
        return current;
    }

    private static Path<?> resolveNestedPath(Path<?> base, String nestedPath) {
        Path<?> current = base;
        for (String segment : nestedPath.split("\\.")) {
            if (!segment.isBlank()) {
                current = current.get(segment.trim());
            }
        }
        return current;
    }

    private static List<?> normalizeAllowed(Collection<?> allowedOrganizations) {
        if (allowedOrganizations == null || allowedOrganizations.isEmpty()) {
            return List.of();
        }
        return allowedOrganizations.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}
