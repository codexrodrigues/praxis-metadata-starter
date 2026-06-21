package org.praxisplatform.uischema.options.service.jpa;

import jakarta.persistence.EntityManager;
import org.springframework.data.jpa.domain.Specification;

/**
 * Internal JPA host context consumed only by the default JPA option-source provider.
 */
public record JpaOptionSourceHostContext<E>(
        EntityManager entityManager,
        Class<E> entityClass,
        Specification<E> specification
) {
}
