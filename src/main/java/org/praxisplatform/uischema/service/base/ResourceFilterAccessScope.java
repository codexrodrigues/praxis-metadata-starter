package org.praxisplatform.uischema.service.base;

import org.springframework.data.jpa.domain.Specification;

import java.util.Objects;

/**
 * Server-resolved row access applied to resource filter queries.
 *
 * <p>The host application owns this authorization decision. Client filters and requested IDs
 * must never select or relax the scope. Use {@link #unrestricted()} only when the resource is
 * intentionally global, {@link #denied()} when the current context has no row access, and
 * {@link #restricted(Specification)} for a mandatory row predicate.</p>
 *
 * @param <E> resource entity type
 */
public final class ResourceFilterAccessScope<E> {

    /** Explicit authorization outcome for the current resource query. */
    public enum Mode {
        UNRESTRICTED,
        RESTRICTED,
        DENIED
    }

    private final Mode mode;
    private final Specification<E> specification;

    private ResourceFilterAccessScope(Mode mode, Specification<E> specification) {
        this.mode = Objects.requireNonNull(mode, "mode must not be null");
        this.specification = Objects.requireNonNull(specification, "specification must not be null");
    }

    /** Returns an explicit global scope for resources without row-level restrictions. */
    public static <E> ResourceFilterAccessScope<E> unrestricted() {
        return new ResourceFilterAccessScope<>(
                Mode.UNRESTRICTED,
                (root, query, criteriaBuilder) -> criteriaBuilder.conjunction()
        );
    }

    /** Returns a fail-closed scope that exposes no rows. */
    public static <E> ResourceFilterAccessScope<E> denied() {
        return new ResourceFilterAccessScope<>(
                Mode.DENIED,
                (root, query, criteriaBuilder) -> criteriaBuilder.disjunction()
        );
    }

    /** Returns a mandatory server-owned row predicate. */
    public static <E> ResourceFilterAccessScope<E> restricted(Specification<E> specification) {
        return new ResourceFilterAccessScope<>(Mode.RESTRICTED, specification);
    }

    public Mode mode() {
        return mode;
    }

    public Specification<E> specification() {
        return specification;
    }
}
